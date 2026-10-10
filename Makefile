.PHONY: android android-aar all build doubletake doubletake-ctl doubletake-test-receiver doubletake-release doubletake-ctl-release doubletake-test-receiver-release manpages-release install install-man uninstall test clean

PREFIX ?= /usr/local
MANDIR ?= $(PREFIX)/share/man

all: doubletake doubletake-ctl doubletake-test-receiver

build: all

doubletake:
	go build -o bin/doubletake ./cmd/doubletake

doubletake-ctl:
	go build -o bin/doubletake-ctl ./cmd/doubletake-ctl

doubletake-test-receiver:
	go build -o bin/doubletake-test-receiver ./cmd/doubletake-test-receiver

doubletake-release:
	CGO_ENABLED=0 go build -ldflags='-s -w -extldflags=-static' -o doubletake ./cmd/doubletake

doubletake-ctl-release:
	CGO_ENABLED=0 go build -ldflags='-s -w -extldflags=-static' -o doubletake-ctl ./cmd/doubletake-ctl

doubletake-test-receiver-release:
	CGO_ENABLED=0 go build -ldflags='-s -w -extldflags=-static' -o doubletake-test-receiver ./cmd/doubletake-test-receiver

manpages-release:
	tar -czf doubletake-manpages.tar.gz -C man man1

test:
	go test ./...

install: all install-man
	install -m 755 bin/doubletake $(PREFIX)/bin/
	install -m 755 bin/doubletake-ctl $(PREFIX)/bin/
	install -m 755 bin/doubletake-test-receiver $(PREFIX)/bin/

install-man:
	install -d $(MANDIR)/man1
	install -m 644 man/man1/doubletake.1 $(MANDIR)/man1/
	install -m 644 man/man1/doubletake-ctl.1 $(MANDIR)/man1/
	install -m 644 man/man1/doubletake-test-receiver.1 $(MANDIR)/man1/

uninstall:
	rm -f $(PREFIX)/bin/doubletake
	rm -f $(PREFIX)/bin/doubletake-ctl
	rm -f $(PREFIX)/bin/doubletake-test-receiver
	rm -f $(MANDIR)/man1/doubletake.1
	rm -f $(MANDIR)/man1/doubletake-ctl.1
	rm -f $(MANDIR)/man1/doubletake-test-receiver.1

clean:
	rm -rf bin/
	go clean -testcache

# Android app. Needs gomobile, the Android SDK/NDK (ANDROID_HOME) and JDK 17+.
# gomobile wants golang.org/x/mobile in go.mod, which also raises the Go version;
# keep that out of the tree by restoring go.mod/go.sum afterwards.
ANDROID_TARGETS ?= android/arm64,android/amd64

android-aar:
	cp go.mod go.mod.bak && cp go.sum go.sum.bak; \
	go get golang.org/x/mobile/bind && \
	gomobile bind -target $(ANDROID_TARGETS) -androidapi 29 -javapkg com.doubletake -o android/app/libs/doubletake.aar ./mobile; \
	rc=$$?; mv go.mod.bak go.mod; mv go.sum.bak go.sum; exit $$rc

android: android-aar
	cd android && ./gradlew assembleDebug
