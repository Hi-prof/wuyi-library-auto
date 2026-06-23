#!/bin/bash
# scripts/build-css.sh
set -e
cd "$(dirname "$0")/.."
npm run build:css
echo "✓ CSS built successfully"
