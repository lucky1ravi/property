#!/usr/bin/env bash
export PLAYWRIGHT_BROWSERS_PATH=${PLAYWRIGHT_BROWSERS_PATH:-/opt/pw-browsers}
exec npx playwright test "$@"
