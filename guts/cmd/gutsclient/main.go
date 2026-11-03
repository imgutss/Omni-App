// Command gutsclient runs the guts SOCKS5 proxy on this machine: every CONNECT it receives is
// carried through a door to the exit node, which opens the real connection.
//
//	gutsclient -door https://script.google.com/macros/s/<id>/exec -key <secret> \
//	           -google-ip 216.239.38.120 -sni www.google.com -listen 127.0.0.1:1080
//
// With -direct the door is an exit node itself, which is how a local test or a home server runs.
package main

import (
	"context"
	"flag"
	"fmt"
	"log"
	"net/url"
	"os"
	"os/signal"
	"syscall"
	"time"

	"guts/relay"
)

func main() {
	listen := flag.String("listen", "127.0.0.1:1080", "SOCKS5 listen address")
	doorURL := flag.String("door", "", "door URL: an Apps Script /exec URL, or an exit node endpoint")
	key := flag.String("key", os.Getenv("GUTS_KEY"), "shared key")
	googleIP := flag.String("google-ip", "", "Google edge address to dial instead of the door's own address")
	sni := flag.String("sni", "www.google.com", "SNI used on the connection to the Google edge")
	direct := flag.Bool("direct", false, "post straight to the exit node at -door, no fronting")
	check := flag.Bool("check", false, "ping the door and exit without starting the proxy")
	flag.Parse()

	if *doorURL == "" {
		log.Fatal("gutsclient: -door is required")
	}
	parsed, err := url.Parse(*doorURL)
	if err != nil {
		log.Fatalf("gutsclient: bad -door: %v", err)
	}

	var door relay.Door
	if *direct || *googleIP == "" {
		door = relay.NewDirect(*doorURL, *key)
	} else {
		fronted, err := relay.NewFronted(parsed, *googleIP, *sni, *key)
		if err != nil {
			log.Fatalf("gutsclient: %v", err)
		}
		door = fronted
	}

	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	if *check {
		info, err := relay.Check(ctx, door)
		if err != nil {
			fmt.Println("door check failed:", err)
			os.Exit(1)
		}
		fmt.Println("door ok:", info, "via", door.Describe())
		return
	}

	proxy := relay.NewProxy(door, *listen, func(format string, args ...any) { log.Printf(format, args...) })
	addr, err := proxy.Listen()
	if err != nil {
		log.Fatalf("gutsclient: %v", err)
	}
	log.Printf("guts client on socks5://%s via %s", addr, door.Describe())

	go func() {
		sig := make(chan os.Signal, 1)
		signal.Notify(sig, syscall.SIGINT, syscall.SIGTERM)
		<-sig
		log.Printf("guts client: stopping")
		_ = proxy.Close()
	}()
	if err := proxy.Serve(); err != nil {
		log.Fatalf("gutsclient: %v", err)
	}
}
