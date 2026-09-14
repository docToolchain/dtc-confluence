#!/bin/sh
# Installs the dtc-confluence command line tool from this directory.
#
# Copies the launcher and the jar under a prefix and says what to do about PATH. Nothing is
# downloaded and nothing outside the prefix is touched, so uninstalling is a single rm.
set -eu

usage() {
    cat <<'USAGE'
Usage: ./install.sh [--prefix DIR] [--force]

  --prefix DIR  where to install; default $HOME/.local
                the launcher goes to DIR/bin, the jar to DIR/lib/dtc-confluence
  --force       overwrite an existing installation
  -h, --help    show this text
USAGE
}

prefix="${HOME}/.local"
force=no

while [ $# -gt 0 ]; do
    case "$1" in
        --prefix) [ $# -ge 2 ] || { echo "--prefix needs a directory" >&2; exit 2; }
                  prefix="$2"; shift 2 ;;
        --force)  force=yes; shift ;;
        -h|--help) usage; exit 0 ;;
        *) echo "unknown argument: $1" >&2; usage >&2; exit 2 ;;
    esac
done

here=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
[ -f "${here}/lib/dtc-confluence.jar" ] || {
    echo "install.sh: no lib/dtc-confluence.jar next to this script - run 'mvn package' first" >&2
    exit 1
}

bin_dir="${prefix}/bin"
lib_dir="${prefix}/lib/dtc-confluence"
launcher="${bin_dir}/dtc-confluence"

# Either half of an installation counts as one: a launcher that was removed by hand must not let
# the jar beneath it be replaced unasked.
if [ "${force}" = no ]; then
    for path in "${launcher}" "${lib_dir}/dtc-confluence.jar"; do
        if [ -e "${path}" ] || [ -L "${path}" ]; then
            echo "install.sh: ${path} exists already; pass --force to replace it" >&2
            exit 1
        fi
    done
fi

# Nothing outside the prefix may be written. Copying onto a symlink writes through it, so a
# symlinked destination is refused rather than followed - --force replaces an installation, it
# does not overrule where the files land.
for path in "${bin_dir}" "${lib_dir}" "${launcher}" "${lib_dir}/dtc-confluence.jar"; do
    if [ -L "${path}" ]; then
        echo "install.sh: ${path} is a symlink; refusing to write through it" >&2
        exit 1
    fi
done

mkdir -p "${bin_dir}" "${lib_dir}"
rm -f "${lib_dir}/dtc-confluence.jar" "${launcher}"
cp "${here}/lib/dtc-confluence.jar" "${lib_dir}/dtc-confluence.jar"
cp "${here}/bin/dtc-confluence" "${launcher}"
chmod 755 "${launcher}"

echo "Installed:"
echo "  ${launcher}"
echo "  ${lib_dir}/dtc-confluence.jar"

case ":${PATH}:" in
    *":${bin_dir}:"*) echo "Run it with: dtc-confluence --help" ;;
    *) echo
       echo "${bin_dir} is not on your PATH. Either add it:"
       echo "  export PATH=\"${bin_dir}:\$PATH\""
       echo "or call the launcher directly:"
       echo "  ${launcher} --help" ;;
esac
