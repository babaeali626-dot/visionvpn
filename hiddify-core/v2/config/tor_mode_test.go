package config

import (
	"testing"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
)

func TestBuildConfig_NormalHiddifyPreservedWhenTorDisabled(t *testing.T) {
	opt := DefaultHiddifyOptions()
	opt.EnableTun = true
	opt.Tor.Enabled = false

	input := option.Options{
		Outbounds: []option.Outbound{
			{
				Type: C.TypeSOCKS,
				Tag:  "user-proxy",
				SocksOptions: option.SocksOutboundOptions{
					ServerOptions: option.ServerOptions{Server: "203.0.113.10", ServerPort: 1080},
				},
			},
		},
	}

	built, err := BuildConfig(*opt, input)
	if err != nil {
		t.Fatalf("normal BuildConfig failed: %v", err)
	}

	var hasSelect, hasDirect, hasTor bool
	for _, out := range built.Outbounds {
		if out.Tag == OutboundSelectTag {
			hasSelect = true
		}
		if out.Type == C.TypeDirect {
			hasDirect = true
		}
		if out.Tag == OutboundTorTag {
			hasTor = true
		}
	}
	if !hasSelect || !hasDirect {
		t.Fatalf("expected normal Hiddify select and direct outbounds to remain intact")
	}
	if hasTor {
		t.Fatalf("Tor outbound must not be injected when Tor mode is disabled")
	}
}

func TestBuildConfig_TorModeEnforcesZeroDirectZeroUDPAndTorDNS(t *testing.T) {
	opt := DefaultHiddifyOptions()
	opt.EnableTun = true
	opt.BypassLAN = true
	opt.AllowConnectionFromLAN = true
	opt.Tor = TorModeOptions{
		Enabled:   true,
		SocksHost: "127.0.0.1",
		SocksPort: 41234,
		Transport: "snowflake",
	}

	// Even if a normal profile input is passed, Tor mode must ignore it and never add direct/proxy outbounds.
	built, err := BuildConfig(*opt, option.Options{})
	if err != nil {
		t.Fatalf("Tor BuildConfig failed: %v", err)
	}

	// 1. Verify NTP is disabled.
	if built.NTP != nil {
		t.Fatalf("NTP must be disabled in Tor mode to prevent direct UDP/123 leaks")
	}

	// 2. Verify outbounds: ONLY tor (socks5 TCP on loopback), dns-out, block; ZERO direct outbounds.
	var torOut *option.Outbound
	for i := range built.Outbounds {
		out := &built.Outbounds[i]
		if out.Type == C.TypeDirect {
			t.Fatalf("direct outbound %q is forbidden in Tor mode", out.Tag)
		}
		if out.Tag == OutboundTorTag {
			torOut = out
		}
	}
	if torOut == nil {
		t.Fatalf("missing Tor outbound")
	}
	if torOut.Type != C.TypeSOCKS || torOut.SocksOptions.Server != "127.0.0.1" || torOut.SocksOptions.ServerPort != 41234 {
		t.Fatalf("unexpected Tor outbound config: %+v", torOut.SocksOptions)
	}
	if torOut.SocksOptions.Version != "5" || torOut.SocksOptions.Network != "tcp" {
		t.Fatalf("Tor outbound must be SOCKS5 TCP-only, got version=%q network=%q", torOut.SocksOptions.Version, torOut.SocksOptions.Network)
	}

	// 3. Verify DNS: strictly detoured through Tor, zero local/direct servers, zero AddressResolver.
	if built.DNS == nil || built.DNS.Final != DNSTorTag {
		t.Fatalf("DNS final must be %q", DNSTorTag)
	}
	for _, srv := range built.DNS.Servers {
		if srv.Address == "local" || srv.Tag == DNSDirectTag || srv.Tag == DNSLocalTag || srv.Tag == DNSTricksDirectTag {
			t.Fatalf("forbidden direct/local DNS server in Tor mode: %+v", srv)
		}
		if srv.Tag == DNSTorTag {
			if srv.Detour != OutboundTorTag {
				t.Fatalf("dns-tor must detour through %q, got %q", OutboundTorTag, srv.Detour)
			}
			if srv.AddressResolver != "" {
				t.Fatalf("dns-tor must not depend on an AddressResolver, got %q", srv.AddressResolver)
			}
		}
	}

	// 4. Verify Routing: fail-closed final=block, UDP blocked, TCP -> tor, DNS hijacked.
	if built.Route == nil || built.Route.Final != OutboundBlockTag {
		t.Fatalf("Route.Final must be %q (fail-closed), got %+v", OutboundBlockTag, built.Route)
	}
	var hasUDPBlock, hasTCPTor, hasDNSHijack bool
	for _, rule := range built.Route.Rules {
		d := rule.DefaultOptions
		if len(d.Network) == 1 && d.Network[0] == "udp" && d.Outbound == OutboundBlockTag {
			hasUDPBlock = true
		}
		if len(d.Network) == 1 && d.Network[0] == "tcp" && d.Outbound == OutboundTorTag {
			hasTCPTor = true
		}
		if len(d.Port) == 1 && d.Port[0] == 53 && d.Outbound == OutboundDNSTag {
			hasDNSHijack = true
		}
		if d.Outbound == OutboundDirectTag || d.Outbound == OutboundBypassTag {
			t.Fatalf("direct/bypass route rule is forbidden in Tor mode: %+v", d)
		}
	}
	if !hasUDPBlock || !hasTCPTor || !hasDNSHijack {
		t.Fatalf("missing required route rules: udpBlock=%v tcpTor=%v dnsHijack=%v", hasUDPBlock, hasTCPTor, hasDNSHijack)
	}
}

func TestValidateTorModeOptions_RejectsNonLoopbackAndInvalidPort(t *testing.T) {
	if err := ValidateTorModeOptions(TorModeOptions{Enabled: true, SocksHost: "0.0.0.0", SocksPort: 9050}); err == nil {
		t.Fatalf("expected error for non-loopback host")
	}
	if err := ValidateTorModeOptions(TorModeOptions{Enabled: true, SocksHost: "127.0.0.1", SocksPort: 0}); err == nil {
		t.Fatalf("expected error for port 0")
	}
	if err := ValidateTorModeOptions(TorModeOptions{Enabled: true, SocksHost: "127.0.0.1", SocksPort: 9050}); err != nil {
		t.Fatalf("unexpected error for valid loopback config: %v", err)
	}
}
