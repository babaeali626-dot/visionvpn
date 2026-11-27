package config

import (
	"fmt"
	"net/netip"
	"strings"

	C "github.com/sagernet/sing-box/constant"
	"github.com/sagernet/sing-box/option"
	dns "github.com/sagernet/sing-dns"
)

const (
	OutboundTorTag = "tor"
	DNSTorTag      = "dns-tor"
)

// TorModeOptions configures the standalone Vision VPN Tor mode inside hiddify-core.
// When Enabled is false, normal Hiddify configuration generation is completely untouched.
type TorModeOptions struct {
	Enabled    bool   `json:"enabled,omitempty"`
	SocksHost  string `json:"socks-host,omitempty"`
	SocksPort  uint16 `json:"socks-port,omitempty"`
	Transport  string `json:"transport,omitempty"`
	DNSAddress string `json:"dns-address,omitempty"`
}

// ValidateTorModeOptions enforces loopback-only SOCKS5 binding and valid port ranges.
func ValidateTorModeOptions(tor TorModeOptions) error {
	if !tor.Enabled {
		return nil
	}
	host := strings.TrimSpace(tor.SocksHost)
	if host != "127.0.0.1" && host != "::1" {
		return fmt.Errorf("tor socks host must be loopback (127.0.0.1 or ::1), got %q", host)
	}
	if tor.SocksPort == 0 {
		return fmt.Errorf("tor socks port must be between 1 and 65535")
	}
	return nil
}

// BuildTorConfig generates a strict, fail-closed, TCP-only sing-box configuration for standalone
// Tor mode:
//   - All TCP traffic is routed strictly through the local Tor SOCKS5 listener.
//   - All DNS traffic is hijacked and resolved over TCP/DoH strictly detoured through Tor (no
//     system/direct/local DNS and no direct fallback).
//   - All non-DNS UDP traffic is explicitly blocked.
//   - Zero direct or bypass outbounds exist anywhere in the configuration.
//   - NTP is disabled to prevent direct UDP/123 leaks.
//   - Route.Final is OutboundBlockTag (fail-closed).
func BuildTorConfig(opt HiddifyOptions) (*option.Options, error) {
	if err := ValidateTorModeOptions(opt.Tor); err != nil {
		return nil, err
	}

	dnsAddr := strings.TrimSpace(opt.Tor.DNSAddress)
	if dnsAddr == "" {
		// IP-literal TCP DNS detoured through Tor SOCKS5 requires no bootstrap/direct AddressResolver.
		dnsAddr = "tcp://1.1.1.1"
	}
	if strings.EqualFold(dnsAddr, "local") {
		return nil, fmt.Errorf("local/system DNS is forbidden in Tor mode")
	}

	var options option.Options
	setClashAPI(&options, &opt)
	setLog(&options, &opt)

	// Force loopback-only inbounds and strict TUN routing in Tor mode.
	torInboundOpt := opt
	torInboundOpt.AllowConnectionFromLAN = false
	torInboundOpt.StrictRoute = true
	torInboundOpt.ResolveDestination = false
	setInbound(&options, &torInboundOpt)

	// Strictly disable NTP in Tor mode (prevents direct UDP/123 packets).
	options.NTP = nil

	// DNS strictly over Tor with zero direct/local servers and zero fallback.
	options.DNS = &option.DNSOptions{
		StaticIPs: map[string][]string{},
		DNSClientOptions: option.DNSClientOptions{
			IndependentCache: true,
		},
		Final: DNSTorTag,
		Servers: []option.DNSServerOptions{
			{
				Tag:      DNSTorTag,
				Address:  dnsAddr,
				Strategy: option.DomainStrategy(dns.DomainStrategyUseIPv4),
				Detour:   OutboundTorTag,
			},
			{
				Tag:     DNSBlockTag,
				Address: "rcode://refused",
			},
		},
		Rules: []option.DNSRule{
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultDNSRule{
					Server:       DNSTorTag,
					DisableCache: false,
				},
			},
		},
	}

	// Outbounds: ONLY tor (TCP-only SOCKS5), dns-out, and block.
	// No direct, direct-fragment, bypass, or normal Hiddify proxy outbounds exist.
	options.Outbounds = []option.Outbound{
		{
			Type: C.TypeSOCKS,
			Tag:  OutboundTorTag,
			SocksOptions: option.SocksOutboundOptions{
				ServerOptions: option.ServerOptions{
					Server:     strings.TrimSpace(opt.Tor.SocksHost),
					ServerPort: opt.Tor.SocksPort,
				},
				Version: "5",
				Network: "tcp",
			},
		},
		{
			Type: C.TypeDNS,
			Tag:  OutboundDNSTag,
		},
		{
			Type: C.TypeBlock,
			Tag:  OutboundBlockTag,
		},
	}

	// Routing rules:
	// 1. Hijack DNS inbound and port 53 -> dns-out (resolved strictly via dns-tor over Tor)
	// 2. Block all remaining UDP (QUIC, WebRTC, STUN, etc.)
	// 3. Route all TCP strictly to Tor SOCKS5
	// 4. Final fallback is BLOCK (fail-closed)
	options.Route = &option.RouteOptions{
		AutoDetectInterface: true,
		Final:               OutboundBlockTag,
		Rules: []option.Rule{
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultRule{
					Inbound:  []string{InboundDNSTag},
					Outbound: OutboundDNSTag,
				},
			},
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultRule{
					Protocol: []string{"dns"},
					Outbound: OutboundDNSTag,
				},
			},
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultRule{
					Port:     []uint16{53},
					Outbound: OutboundDNSTag,
				},
			},
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultRule{
					Network:  []string{"udp"},
					Outbound: OutboundBlockTag,
				},
			},
			{
				Type: C.RuleTypeDefault,
				DefaultOptions: option.DefaultRule{
					Network:  []string{"tcp"},
					Outbound: OutboundTorTag,
				},
			},
		},
	}

	// Ensure TUN addresses remain valid even if caller didn't pre-populate IPv6Mode.
	for i := range options.Inbounds {
		if options.Inbounds[i].Type == C.TypeTun && len(options.Inbounds[i].TunOptions.Address) == 0 {
			options.Inbounds[i].TunOptions.Address = []netip.Prefix{
				netip.MustParsePrefix("172.19.0.1/28"),
			}
		}
	}

	return &options, nil
}
