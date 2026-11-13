// Command firefoxclient runs the Mozilla account engine as a child of the Omni app.
//
// It is the same daemon the desktop core runs, without the tray, the terminal status screen or the
// TUN: it opens a SOCKS5 listener the Xray outbound dials, and a loopback API the app uses to sign a
// person in to their own account, read their allowance and choose a location. The API answers this app
// alone — it is guarded by a token this process writes into its own data folder — and nothing the
// account contains leaves the phone except to Mozilla itself.
//
// It is not a daemon and holds no lock on anything: it runs until its standard input closes, which is
// how the app stops it, and until then it writes every log line to standard error, where the app reads
// and redacts them.
package main

import (
	"errors"
	"flag"
	"fmt"
	"io"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/armanlife/arman-vpn-client/core/internal/config"
	"github.com/armanlife/arman-vpn-client/core/internal/logx"
	"github.com/armanlife/arman-vpn-client/core/internal/service"
	"github.com/armanlife/arman-vpn-client/core/internal/sockprotect"
)

// shutdownGrace is how long the daemon gets to close its listeners and sessions before this process
// leaves anyway: the app has already torn the tunnel down, so waiting longer keeps a phone awake for
// nothing.
const shutdownGrace = 10 * time.Second

func main() {
	dataDir := flag.String("data-dir", "", "Data folder of the engine (required); created when missing")
	// The app's login for this engine's loopback API. Given here, it replaces whatever the data folder
	// kept from an earlier run, so only the app that started the engine can call it.
	localToken := flag.String("local-token", "", "Login for the loopback API (the app sets its own)")
	socks := flag.String("socks", config.DefaultProxyListen, "Local SOCKS5 address the Xray outbound dials")
	api := flag.String("api", config.DefaultDashboardListen, "Local API address, guarded by the local token")
	on := flag.Bool("on", true, "Carry traffic as soon as an account can; false starts it stopped")
	// A phone whose whole traffic goes through this process must not also send its sockets out through
	// the same tunnel: VpnService takes them apart, and every connection to Mozilla would go through
	// Mozilla. The app protects the engine's own sockets through the abstract socket it names here
	// (the same SCM_RIGHTS handshake the other engines in this app use).
	protectPath := flag.String("protect-socket", "", "Abstract Unix socket of the app's VpnService protect server")
	// The app runs this engine as its own child, in a process where nothing reads standard input: the
	// default "stop when input ends" would stop the engine the moment it started. Asked for explicitly,
	// it is how a terminal user closes the engine, and how the app can close it too.
	stopOnEOF := flag.Bool("stop-on-eof", false, "Stop when standard input ends (the default when no data folder is given)")
	logDir := flag.Bool("log-file", false, "Also keep the daily log files in the data folder")
	flag.Parse()

	logx.SetOutput(os.Stderr)

	if err := run(*dataDir, *socks, *api, *on, *logDir, *protectPath, *localToken, *stopOnEOF || *dataDir == ""); err != nil {
		logx.Error("firefoxclient: %v", err)
		os.Exit(1)
	}
}

func run(dataDir, socks, api string, on, withLogFile bool, protectSocket, localToken string, stopOnEOF bool) error {
	if dataDir == "" {
		return errors.New("-data-dir is required")
	}
	paths, err := service.ResolveDataPaths(dataDir)
	if err != nil {
		return err
	}
	if withLogFile {
		logx.EnableFile(paths.LogDir, false)
	}
	// The engine is a Linux binary in an Android app, so the certificate store Go looks for on a
	// desktop is not the one on the phone: point it at the roots Android trusts before anything
	// verifies a certificate.
	if err := useDeviceRoots(paths.Dir); err != nil {
		return err
	}
	if err := configure(paths, socks, api, localToken); err != nil {
		return err
	}
	// Protected sockets are the reason this engine can run beside the app's own VpnService: every
	// socket it opens is handed to the app before it is connected, so the traffic of the tunnel does
	// not go through the tunnel.
	sockprotect.Install(protectSocket)
	if sockprotect.Enabled() {
		logx.Info("firefoxclient: this engine's own sockets leave the phone outside the VPN (%s)", protectSocket)
	}
	// A token of an earlier run would let the app trust whatever answers on the API port before this
	// run has bound it; the daemon writes a fresh one when it has.
	_ = os.Remove(paths.LocalToken)

	daemon, err := service.NewDaemon(paths, false)
	if err != nil {
		return fmt.Errorf("the engine did not start: %w", err)
	}
	// Every app on the phone can reach loopback: the API answers this app (the local token) and a
	// browser it signs in with, and nobody else.
	daemon.RequireLocalToken()
	if !on {
		daemon.Off()
	}

	finished := make(chan error, 1)
	go func() { finished <- daemon.Run() }()

	// Started: the listeners are bound by the time Run returns an error or this input closes, and a
	// start that failed is reported as one.
	select {
	case err := <-finished:
		if err != nil {
			return fmt.Errorf("the engine stopped: %w", err)
		}
		return nil
	case <-stopRequested(stopOnEOF):
	}

	logx.Info("firefoxclient: stopping")
	daemon.Shutdown()
	select {
	case err := <-finished:
		if err != nil {
			return fmt.Errorf("the engine stopped with a complaint: %w", err)
		}
		return nil
	case <-time.After(shutdownGrace):
		return nil
	}
}

// stopRequested answers when this process is signalled — which is how the app stops it — or, when asked
// for, when its standard input ends.
func stopRequested(watchInput bool) <-chan struct{} {
	stopped := make(chan struct{})
	signals := make(chan os.Signal, 1)
	signal.Notify(signals, syscall.SIGINT, syscall.SIGTERM)
	if !watchInput {
		go func() {
			defer close(stopped)
			<-signals
		}()
		return stopped
	}
	go func() {
		defer close(stopped)
		select {
		case <-signals:
		case <-eof(os.Stdin):
		}
	}()
	return stopped
}

// eof answers when r has nothing more to give, which for a pipe the app holds means the app closed it.
func eof(r io.Reader) <-chan struct{} {
	closed := make(chan struct{})
	go func() {
		defer close(closed)
		// One byte is enough to know the read ended; the content of the pipe is not used for anything.
		_, _ = io.Copy(io.Discard, r)
	}()
	return closed
}

// configure puts the two addresses this process was told to use into the configuration the daemon
// reads, keeping everything else the folder already had.
func configure(paths service.DataPaths, socks, api, token string) error {
	cfg, _, err := config.Load(paths.Config)
	if err != nil {
		return err
	}
	if socks != "" {
		cfg.Proxy.Listen = socks
	}
	if api != "" {
		cfg.Dashboard.Listen = api
	}
	// Nobody types a password into a phone's loopback API: the local token is the credential, and a
	// dashboard login would only be something to lose.
	cfg.Dashboard.Username = ""
	if token != "" {
		cfg.Dashboard.LocalToken = token
	}
	// The app turns the whole phone's tunnel on; a proxy that sleeps while it is on would be a
	// connection that dies in the pocket.
	cfg.Proxy.IdleSleep = 0
	if err := config.ValidateUpstreamProxy(cfg.Network, cfg.Proxy.Listen); err != nil {
		return err
	}
	return config.Save(paths.Config, cfg)
}
