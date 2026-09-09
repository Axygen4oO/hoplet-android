package main

import (
	"context"
	"net"
	"os"
	"testing"
)

func TestDoHEndpointHelpers(t *testing.T) {
	ep, ok := normalizeDoHEndpoint("https://1.1.1.1")
	if !ok || ep != "https://1.1.1.1/dns-query" {
		t.Fatalf("normalizeDoHEndpoint mismatch: %q %v", ep, ok)
	}
	if got := goDoHEndpointsForArg("doh-google"); len(got) == 0 {
		t.Fatal("expected doh-google endpoints")
	}
}

func TestDoHResolverLookupHost(t *testing.T) {
	if os.Getenv("WDTT_RUN_NETWORK_TESTS") != "1" {
		t.Skip("live DoH test disabled by default")
	}

	setupGlobalResolver("doh-cloudflare")
	addrs, err := net.DefaultResolver.LookupHost(context.Background(), "login.vk.ru")
	if err != nil {
		t.Fatalf("lookup failed: %v", err)
	}
	if len(addrs) == 0 {
		t.Fatal("no addresses")
	}
	t.Logf("addrs=%v", addrs)
}
