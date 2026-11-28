#!/bin/sh
# Starts as root only long enough to make the workspace volume writable, then runs the application as the
# unprivileged "app" user. Platform volumes (Railway, for example) are mounted root-owned and hide the
# ownership set in the image, so the application could not write extracted repositories there.
set -eu

if [ "$(id -u)" = "0" ]; then
	WORKSPACE="${WORKSPACE_ROOT:-/workspace}"
	mkdir -p "$WORKSPACE"
	chown -R app:app "$WORKSPACE" || echo "warning: could not change the owner of $WORKSPACE" >&2
	# Semgrep and the JVM write settings and temporary files under the app user's home.
	export HOME=/app
	exec setpriv --reuid=app --regid=app --init-groups "$@"
fi

exec "$@"
