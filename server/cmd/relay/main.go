package main

import (
	"context"
	"crypto/tls"
	"flag"
	"log"
	"net/http"
	"os"
	"os/signal"
	"pnale/monitor/relay"
	"syscall"
	"time"
)

func main() {
	listen := flag.String("listen", ":443", "HTTPS bind address")
	cert := flag.String("cert", "/etc/pnale-relay/server.crt", "TLS certificate")
	key := flag.String("key", "/etc/pnale-relay/server.key", "TLS private key")
	flag.Parse()
	ctx, cancel := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer cancel()
	h := relay.New(ctx)
	h.Token = os.Getenv("PNALE_API_TOKEN")
	h.Monitor = os.Getenv("PNALE_MONITOR_URL")
	if h.Monitor == "" {
		h.Monitor = "http://127.0.0.1:8797"
	}
	s := &http.Server{Addr: *listen, Handler: h, ReadHeaderTimeout: 5 * time.Second, IdleTimeout: 60 * time.Second, MaxHeaderBytes: 8192, TLSConfig: &tls.Config{MinVersion: tls.VersionTLS12}}
	go func() {
		<-ctx.Done()
		c, stop := context.WithTimeout(context.Background(), 5*time.Second)
		defer stop()
		s.Shutdown(c)
	}()
	log.Printf("Panel public market relay listening on %s", *listen)
	if err := s.ListenAndServeTLS(*cert, *key); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}
