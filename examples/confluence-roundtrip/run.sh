#!/usr/bin/env bash
#
# Bausteinsicht model -> AsciiDoc -> editable draw.io diagram in Confluence,
# end to end. Nothing about draw.io is configured anywhere; the model is the
# only source.
#
# Variant B (one shared draw.io file, several views). Runnable with dtc-confluence
# built from the feature/drawio-aspect-publish branch: its publish resolves the
# shared architecture.drawio from the per-view image file names and emits one
# editable drawio macro per view. The decisions are in ../../docs/decisions.adoc,
# under "Several views of one diagram".
#
# Usage:
#   ./run.sh build              # legs 1-3: local only, writes no Confluence
#   ./run.sh publish            # legs 1-4: build, then publish to Confluence
#   ./run.sh publish --dry-run  # build, then read everything and write nothing
#   ./run.sh back               # export the published page back (proves the
#                               # picture is still the diagram)
#   ./run.sh                    # same as: publish
#
# Tools (override via environment; sensible defaults otherwise):
#   BAUSTEINSICHT        path to the bausteinsicht binary      (default: on PATH)
#   DTC_CONFLUENCE_JAR   path to dtc-confluence.jar            (required to publish/back)
#
# Confluence (all from the environment; no secret ever reaches a command line):
#   CONFLUENCE_API           e.g. https://wiki.example.com/confluence
#   CONFLUENCE_SPACE         space key to publish INTO (one you own)
#   CONFLUENCE_ANCESTOR_ID   id of the parent page new pages hang under
#   CONFLUENCE_BEARER_TOKEN  personal access token (or CONFLUENCE_CREDENTIALS=user:token)
#   CONFLUENCE_ROOT_PAGE_ID  id of the published page, for `back` only
#
# The token is read by dtc-confluence straight from the environment. Keep it in
# direnv or a secrets manager and never put it on a command line or in a file.
set -euo pipefail
cd "$(dirname "$0")"

BAUSTEINSICHT="${BAUSTEINSICHT:-bausteinsicht}"
DTC_CONFLUENCE_JAR="${DTC_CONFLUENCE_JAR:-}"

die() { echo "error: $*" >&2; exit 1; }

require_tool() {
    command -v "$1" >/dev/null 2>&1 || die "'$1' not found on PATH"
}

require_env() {
    local name
    for name in "$@"; do
        [ -n "${!name:-}" ] || die "environment variable $name is not set"
    done
}

# Render a *.template to its target, substituting ${CONFLUENCE_*} from the
# environment. Uses envsubst when present, else a sed fallback for exactly the
# variables this example defines.
render() {
    local template="$1" target="$2"
    if command -v envsubst >/dev/null 2>&1; then
        # The single quotes are the point: envsubst takes the names it may substitute as a
        # literal list, and expanding them here would hand it the values instead - so it would
        # substitute nothing, or whatever the values happen to look like.
        # shellcheck disable=SC2016
        envsubst '${CONFLUENCE_API} ${CONFLUENCE_SPACE} ${CONFLUENCE_ANCESTOR_ID} ${CONFLUENCE_ROOT_PAGE_ID}' \
            <"$template" >"$target"
    else
        sed -e "s|\${CONFLUENCE_API}|${CONFLUENCE_API:-}|g" \
            -e "s|\${CONFLUENCE_SPACE}|${CONFLUENCE_SPACE:-}|g" \
            -e "s|\${CONFLUENCE_ANCESTOR_ID}|${CONFLUENCE_ANCESTOR_ID:-}|g" \
            -e "s|\${CONFLUENCE_ROOT_PAGE_ID}|${CONFLUENCE_ROOT_PAGE_ID:-}|g" \
            "$template" >"$target"
    fi
}

jar() {
    [ -n "$DTC_CONFLUENCE_JAR" ] || die "DTC_CONFLUENCE_JAR is not set (path to dtc-confluence.jar)"
    [ -f "$DTC_CONFLUENCE_JAR" ] || die "DTC_CONFLUENCE_JAR does not point at a file: $DTC_CONFLUENCE_JAR"
    java -jar "$DTC_CONFLUENCE_JAR" "$@"
}

build() {
    require_tool "$BAUSTEINSICHT"
    require_tool asciidoctor
    echo ">>> sync: model -> architecture.drawio (one page per view)"
    "$BAUSTEINSICHT" sync --model architecture.jsonc
    echo ">>> export: one PLAIN SVG per view (no embedded model)"
    # export writes architecture-<key>.svg; dtc-confluence resolves the shared
    # architecture.drawio from these names and matches each tail to a page id in it,
    # so no renaming is needed here.
    "$BAUSTEINSICHT" export --model architecture.jsonc \
        --image-format svg --output .
    # Guard (dtc-confluence finding): a variant-B SVG must NOT carry an embedded
    # model, or the publisher's "file wins" rule silently produces three separate
    # diagrams (variant A) instead of one shared file.
    if grep -lE '(content="[^"]*mxfile|&lt;mxfile|<mxfile)' architecture-*.svg >/dev/null 2>&1; then
        die "exported SVGs carry an embedded model; variant B needs PLAIN svg (no --embed-diagram)"
    fi
    echo ">>> asciidoctor: architecture.adoc -> architecture.html"
    asciidoctor -o architecture.html architecture.adoc
}

publish() {
    require_env CONFLUENCE_API CONFLUENCE_SPACE CONFLUENCE_ANCESTOR_ID
    require_env CONFLUENCE_BEARER_TOKEN
    build
    render dtc-confluence.yaml.template .dtc-confluence.yaml
    # dtc-confluence resolves the shared architecture.drawio from the image names,
    # attaches it once, and emits one drawio macro per view with its aspect
    # (links=none). Needs dtc-confluence built from feature/drawio-aspect-publish.
    echo ">>> publish: architecture.html -> Confluence space $CONFLUENCE_SPACE"
    jar publish -c .dtc-confluence.yaml "$@"
}

back() {
    require_env CONFLUENCE_API CONFLUENCE_SPACE CONFLUENCE_ROOT_PAGE_ID
    require_env CONFLUENCE_BEARER_TOKEN
    render dtc-confluence-back.yaml.template .dtc-confluence-back.yaml
    echo ">>> export: page $CONFLUENCE_ROOT_PAGE_ID -> back/"
    jar export -c .dtc-confluence-back.yaml
}

cmd="${1:-publish}"
case "$cmd" in
    build)   build ;;
    publish) shift || true; publish "$@" ;;
    back)    back ;;
    *)       die "unknown command: $cmd (use build | publish | back)" ;;
esac
