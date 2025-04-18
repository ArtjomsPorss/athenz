// Copyright The Athenz Authors
// Licensed under the terms of the Apache version 2.0 license. See LICENSE file for terms.

package ztsroletoken

import (
	"crypto/tls"
	"crypto/x509"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"sync"
	"time"

	"github.com/AthenZ/athenz/clients/go/zts"
	"github.com/AthenZ/athenz/libs/go/zmssvctoken"
)

const (
	defaultPrincipalAuthHeader = "Athenz-Principal-Auth"
)

var expirationDrift = 10 * time.Minute
var defaultPrefetchInterval = 10 * time.Minute

// RoleToken is a mechanism to get a role token (ztoken)
// as a string. It guarantees that the returned token has
// not expired.
type RoleToken interface {
	RoleTokenValue() (string, error)
	StartPrefetcher() error
	StopPrefetcher() error
}

// RoleTokenOptions allows the caller to supply additional options
// for getting a role token. The zero-value is a valid configuration.
type RoleTokenOptions struct {
	BaseZTSURL       string        // the base ZTS URL to use
	ProxyURL         string        // the proxy URL for accessing ZTS
	Role             string        // the single role for which a token is required
	MinExpire        time.Duration // the minimum expiry of the token in (server default if zero)
	MaxExpire        time.Duration // the maximum expiry of the token (server default if zero)
	AuthHeader       string        // Auth Header to use while making ZMS calls
	CACert           []byte        // Optional CA certpem to validate the ZTS server
	PrefetchInterval time.Duration // the interval at which the role token cache is refreshed in the background
}

type roleToken struct {
	domain          string
	opts            RoleTokenOptions
	l               sync.RWMutex
	tok             zmssvctoken.Token
	certFile        string
	keyFile         string
	zToken          string
	expireTime      time.Time
	ticker          *time.Ticker
	tickerLock      sync.Mutex
	isTickerStarted bool
	stopCh          chan struct{}
}

func getClientTLSConfig(certFile, keyFile string) (*tls.Config, error) {
	certpem, err := os.ReadFile(certFile)
	if err != nil {
		return nil, err
	}

	keypem, err := os.ReadFile(keyFile)
	if err != nil {
		return nil, err
	}

	clientCert, err := tls.X509KeyPair(certpem, keypem)
	if err != nil {
		return nil, err
	}

	config := &tls.Config{}
	config.Certificates = make([]tls.Certificate, 1)
	config.Certificates[0] = clientCert

	return config, nil
}

func (r *roleToken) updateRoleToken() (string, error) {
	durationToExpireSeconds := func(d time.Duration) *int32 {
		if d == 0 {
			return nil
		}
		e := int32(d / time.Second)
		return &e
	}

	if r.opts.BaseZTSURL == "" {
		return "", errors.New("BaseZTSURL is empty")
	}

	var proxyURL *url.URL
	if r.opts.ProxyURL != "" {
		p, err := url.Parse(r.opts.ProxyURL)
		if err != nil {
			return "", err
		} else {
			proxyURL = p
		}
	}

	r.l.Lock()
	defer r.l.Unlock()

	var z zts.ZTSClient
	if r.certFile != "" && r.keyFile != "" {
		// Use ZTS Client with TLS cert
		config, err := getClientTLSConfig(r.certFile, r.keyFile)
		if err != nil {
			return "", err
		}

		if len(r.opts.CACert) != 0 {
			certPool := x509.NewCertPool()
			if !certPool.AppendCertsFromPEM(r.opts.CACert) {
				return "", fmt.Errorf("Failed to append certs to pool")
			}
			config.RootCAs = certPool
		}

		tr := http.Transport{
			TLSClientConfig: config,
		}
		if proxyURL != nil {
			tr.Proxy = http.ProxyURL(proxyURL)
		}
		z = zts.NewClient(r.opts.BaseZTSURL, &tr)
	} else {
		ntoken, err := r.tok.Value()
		if err != nil {
			return "", err
		}
		if proxyURL != nil {
			z = zts.NewClient(r.opts.BaseZTSURL, &http.Transport{
				Proxy: http.ProxyURL(proxyURL),
			})
		} else {
			z = zts.NewClient(r.opts.BaseZTSURL, nil)
		}
		z.AddCredentials(r.opts.AuthHeader, ntoken)
	}

	rt, err := z.GetRoleToken(
		zts.DomainName(r.domain),
		zts.EntityList(r.opts.Role),
		durationToExpireSeconds(r.opts.MinExpire),
		durationToExpireSeconds(r.opts.MaxExpire),
		zts.EntityName(""),
	)
	if err != nil {
		return "", err
	}
	r.zToken = rt.Token
	r.expireTime = time.Unix(rt.ExpiryTime, 0)
	return r.zToken, nil
}

func (r *roleToken) RoleTokenValue() (string, error) {
	r.l.RLock()
	ztok := r.zToken
	e := r.expireTime
	r.l.RUnlock()

	if time.Now().Add(expirationDrift).After(e) {
		return r.updateRoleToken()
	}
	return ztok, nil
}

func (r *roleToken) StartPrefetcher() error {
	r.tickerLock.Lock()
	defer r.tickerLock.Unlock()
	if r.isTickerStarted {
		return fmt.Errorf("Prefetcher has already been started")
	}
	prefetchInterval := defaultPrefetchInterval
	if r.opts.PrefetchInterval > 0 {
		prefetchInterval = r.opts.PrefetchInterval
	}
	r.ticker = time.NewTicker(prefetchInterval)
	r.stopCh = make(chan struct{})
	r.isTickerStarted = true
	go func() {
		for {
			select {
			case <-r.stopCh:
				return
			case <-r.ticker.C:
				r.updateRoleToken()
			}
		}
	}()
	return nil
}

func (r *roleToken) StopPrefetcher() error {
	r.tickerLock.Lock()
	defer r.tickerLock.Unlock()
	if !r.isTickerStarted {
		return fmt.Errorf("Prefetcher has already been stopped")
	}
	if r.ticker != nil {
		r.ticker.Stop()
	}
	if r.stopCh != nil {
		close(r.stopCh)
	}
	r.isTickerStarted = false
	return nil
}

// NewRoleToken returns a RoleToken implementation based on principal tokens
// retrieved from the supplied Token implementation for the supplied domain
// and options.
func NewRoleToken(tok zmssvctoken.Token, domain string, opts RoleTokenOptions) *roleToken {
	if opts.AuthHeader == "" {
		opts.AuthHeader = defaultPrincipalAuthHeader
	}
	rt := &roleToken{
		tok:    tok,
		domain: domain,
		opts:   opts,
	}
	return rt
}

// NewRoleTokenFromCert returns a RoleToken implementation based on principal service certificate
// retrieved from the supplied service certificate for the supplied domain
// and options.
func NewRoleTokenFromCert(certFile, keyFile, domain string, opts RoleTokenOptions) *roleToken {
	if opts.AuthHeader == "" {
		opts.AuthHeader = defaultPrincipalAuthHeader
	}
	rt := &roleToken{
		certFile: certFile,
		keyFile:  keyFile,
		domain:   domain,
		opts:     opts,
	}
	return rt
}
