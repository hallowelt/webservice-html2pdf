#!/usr/bin/env bash
# Validates PDFs with veraPDF (https://verapdf.org) running in Docker.
#
# Usage: ./validate-pdf.sh <file.pdf> [more.pdf ...]
#
# Each file is checked against PDF/UA-1 (accessibility) and PDF/A-3u (archiving),
# the two conformance levels the service targets. For failed rules, the rule ID,
# its description and the number of failed checks are listed.
# Set VERAPDF_IMAGE to use another image tag (default: verapdf/cli:latest).
set -euo pipefail

if [ "$#" -eq 0 ]; then
	echo "Usage: $0 <file.pdf> [more.pdf ...]" >&2
	exit 2
fi

image="${VERAPDF_IMAGE:-verapdf/cli:latest}"
status=0

for pdf in "$@"; do
	if [ ! -f "$pdf" ]; then
		echo "Not a file: $pdf" >&2
		status=1
		continue
	fi
	dir="$(cd "$(dirname "$pdf")" && pwd)"
	name="$(basename "$pdf")"
	for flavour in ua1 3u; do
		if ! docker run --rm -v "$dir:/data:ro" "$image" \
				--flavour "$flavour" --format text --verbose "/data/$name"; then
			status=1
		fi
	done
done

exit "$status"
