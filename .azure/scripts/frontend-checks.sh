#!/usr/bin/env bash
set -euo pipefail

cd web-view
npm ci
npm run lint
npm run test
npm run build
