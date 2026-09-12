#!/usr/bin/env bash
# Runs the checks from .github/workflows/lint.yml against the working tree.
#
# CI drives them through super-linter, which publishes no arm64 image; emulating it
# costs minutes before the first check even starts, which is too slow for something
# meant to run before a push. This calls the tools directly instead, with the same
# configuration files, so findings match what CI reports.
#
# Missing tools are reported and skipped rather than failing the run.
set -uo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.." || exit 1

failed=0
skipped=()

have() { command -v "$1" >/dev/null 2>&1; }

report() {
  if [ "$1" -eq 0 ]; then
    printf '  %-22s ok\n' "$2"
  else
    printf '  %-22s FAILED\n' "$2"
    failed=1
  fi
}

echo "linting $(pwd)"

if have actionlint; then
  actionlint .github/workflows/*.yml
  report $? actionlint
else
  skipped+=("actionlint (brew install actionlint)")
fi

if have yamllint; then
  yamllint -c .github/linters/.yamllint.yml .github
  report $? yamllint
else
  skipped+=("yamllint (brew install yamllint)")
fi

if have editorconfig-checker; then
  editorconfig-checker -config .github/linters/.editorconfig-checker.json \
    -exclude '(mvnw|mvnw\.cmd|\.mvn/)'
  report $? editorconfig-checker
else
  skipped+=("editorconfig-checker (brew install editorconfig-checker)")
fi

if have xmllint; then
  find . -name 'pom.xml' -not -path './*/target/*' -print0 | xargs -0 xmllint --noout
  report $? xmllint
else
  skipped+=("xmllint (brew install libxml2)")
fi

if have shellcheck; then
  shellcheck tools/*.sh
  report $? shellcheck
else
  skipped+=("shellcheck (brew install shellcheck)")
fi

if have gitleaks; then
  gitleaks detect --no-banner --redact
  report $? gitleaks
else
  skipped+=("gitleaks (brew install gitleaks)")
fi

if [ ${#skipped[@]} -gt 0 ]; then
  echo
  echo "not installed, so not checked:"
  printf '  %s\n' "${skipped[@]}"
fi

exit "$failed"
