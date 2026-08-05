package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"
	"time"
)

const latestAppReleasesAPI = "https://api.github.com/repos/Axygen4oO/hoplet-android/releases?per_page=30"

type githubRelease struct {
	TagName     string               `json:"tag_name"`
	Body        string               `json:"body"`
	Draft       bool                 `json:"draft"`
	Prerelease  bool                 `json:"prerelease"`
	PublishedAt string               `json:"published_at"`
	CreatedAt   string               `json:"created_at"`
	Assets      []githubReleaseAsset `json:"assets"`
}

type githubReleaseAsset struct {
	Name               string `json:"name"`
	BrowserDownloadURL string `json:"browser_download_url"`
	Size               int64  `json:"size"`
}

type appLatestResponse struct {
	Version     string   `json:"version"`
	ReleaseDate string   `json:"release_date"`
	ApkSize     int64    `json:"apk_size"`
	Changelog   []string `json:"changelog"`
	DownloadURL string   `json:"download_url"`
}

type cachedAppRelease struct {
	version     string
	releaseDate string
	apkSize     int64
	changelog   []string
	assetURL    string
	cachedAt    time.Time
}

func (release cachedAppRelease) toResponse() appLatestResponse {
	return appLatestResponse{
		Version:     release.version,
		ReleaseDate: release.releaseDate,
		ApkSize:     release.apkSize,
		Changelog:   append([]string(nil), release.changelog...),
		DownloadURL: release.assetURL,
	}
}

var (
	appReleaseCache   cachedAppRelease
	appReleaseCacheMu sync.RWMutex
)

func apkHandler(w http.ResponseWriter, r *http.Request) {
	token := getTokenFromRequest(r)
	if token == "" {
		token = r.URL.Query().Get("token")
	}

	if token == "" {
		http.Error(w, "missing token", http.StatusUnauthorized)
		return
	}

	if _, err := ValidateJWT(token); err != nil {
		http.Error(w, "invalid token", http.StatusUnauthorized)
		return
	}

	release, err := resolveLatestAppRelease(shouldRefreshAppRelease(r))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}

	if release.assetURL == "" {
		http.Error(w, "apk not found in latest stable release", http.StatusNotFound)
		return
	}

	http.Redirect(w, r, release.assetURL, http.StatusFound)
}

func resolveLatestAppRelease(force bool) (cachedAppRelease, error) {
	appReleaseCacheMu.RLock()
	cached := appReleaseCache
	if !force && !cached.cachedAt.IsZero() && time.Since(cached.cachedAt) < 10*time.Minute && cached.assetURL != "" {
		appReleaseCacheMu.RUnlock()
		return cached, nil
	}
	appReleaseCacheMu.RUnlock()

	fresh, err := fetchLatestAppRelease()
	if err == nil {
		appReleaseCacheMu.Lock()
		appReleaseCache = fresh
		appReleaseCacheMu.Unlock()
		return fresh, nil
	}

	if cached.assetURL != "" {
		return cached, nil
	}

	return cachedAppRelease{}, err
}

func fetchLatestAppRelease() (cachedAppRelease, error) {
	client := &http.Client{Timeout: 15 * time.Second}

	req, err := http.NewRequest(http.MethodGet, latestAppReleasesAPI, nil)
	if err != nil {
		return cachedAppRelease{}, err
	}

	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("User-Agent", "Hoplet-Server")

	resp, err := client.Do(req)
	if err != nil {
		return cachedAppRelease{}, err
	}
	defer resp.Body.Close()

	if resp.StatusCode != http.StatusOK {
		return cachedAppRelease{}, fmt.Errorf("github returned status %d", resp.StatusCode)
	}

	var releases []githubRelease
	if err := json.NewDecoder(resp.Body).Decode(&releases); err != nil {
		return cachedAppRelease{}, err
	}

	for _, release := range releases {
		if release.Draft || release.Prerelease {
			continue
		}

		asset := selectAPKAsset(release.Assets)
		if asset == nil || strings.TrimSpace(asset.BrowserDownloadURL) == "" {
			continue
		}

		version := normalizeReleaseVersion(release.TagName)
		if version == "" {
			continue
		}

		return cachedAppRelease{
			version:     version,
			releaseDate: normalizeReleaseDate(release.PublishedAt, release.CreatedAt),
			apkSize:     asset.Size,
			changelog:   parseReleaseChangelog(release.Body),
			assetURL:    strings.TrimSpace(asset.BrowserDownloadURL),
			cachedAt:    time.Now(),
		}, nil
	}

	return cachedAppRelease{}, errors.New("apk not found in latest stable release")
}

func shouldRefreshAppRelease(r *http.Request) bool {
	if r == nil {
		return false
	}

	switch strings.ToLower(strings.TrimSpace(r.URL.Query().Get("refresh"))) {
	case "1", "true", "yes", "force":
		return true
	default:
		return false
	}
}

func selectAPKAsset(assets []githubReleaseAsset) *githubReleaseAsset {
	for i := range assets {
		asset := &assets[i]
		if strings.EqualFold(strings.TrimSpace(asset.Name), "app-universal-release.apk") {
			return asset
		}
	}

	for i := range assets {
		asset := &assets[i]
		if strings.HasSuffix(strings.ToLower(strings.TrimSpace(asset.Name)), ".apk") {
			return asset
		}
	}

	return nil
}

func parseReleaseChangelog(body string) []string {
	lines := strings.Split(body, "\n")
	items := make([]string, 0, len(lines))
	for _, line := range lines {
		text := strings.TrimSpace(line)
		if text == "" || strings.HasPrefix(text, "#") {
			continue
		}

		text = strings.TrimLeft(text, "-*•·–— \t")
		text = strings.TrimSpace(text)
		if text == "" {
			continue
		}

		items = append(items, text)
	}

	return items
}

func normalizeReleaseDate(publishedAt, createdAt string) string {
	for _, raw := range []string{publishedAt, createdAt} {
		if raw == "" {
			continue
		}

		if parsed, err := time.Parse(time.RFC3339, raw); err == nil {
			return parsed.UTC().Format("2006-01-02")
		}
	}

	return ""
}

func normalizeReleaseVersion(tag string) string {
	trimmed := strings.TrimSpace(tag)
	if trimmed == "" {
		return ""
	}

	if strings.HasPrefix(trimmed, "v") || strings.HasPrefix(trimmed, "V") {
		return trimmed
	}

	return "v" + trimmed
}
