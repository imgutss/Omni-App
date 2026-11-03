// Command exitnode runs the guts exit node: it holds the real TCP sockets to the internet and is
// reached only through the door (a Google Apps Script web app), never by the phone directly.
//
//	guts-exit -addr :8080 -key <shared-secret>
//
// It runs unchanged on a VPS, on a free CI runner behind a tunnel, or at home.
package main

import (
	"flag"
	"io"
	"log"
	"net/http"
	"os"
	"strconv"
	"time"

	"guts/exitnode"
	"guts/proto"
)

func main() {
	addr := flag.String("addr", ":8080", "listen address")
	path := flag.String("path", "/g", "path of the guts endpoint")
	key := flag.String("key", os.Getenv("GUTS_KEY"), "shared key, also read from GUTS_KEY")
	flag.Parse()

	if *key == "" {
		log.Fatal("guts-exit: a shared key is required (-key or GUTS_KEY)")
	}

	node := exitnode.New(*key)

	mux := http.NewServeMux()
	mux.Handle(*path, node)
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.WriteString(w, "ok sessions="+strconv.Itoa(node.LiveSessions()))
	})

	srv := &http.Server{
		Addr:              *addr,
		Handler:           mux,
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       120 * time.Second,
	}
	log.Printf("guts-exit %s listening on %s%s", proto.VersionString(), *addr, *path)
	log.Fatal(srv.ListenAndServe())
}
