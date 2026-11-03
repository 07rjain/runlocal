#!/usr/bin/env bash
set -euo pipefail
project_root="$(cd "$(dirname "$0")/.." && pwd)"
dependency_path="$project_root/third_party/stable-diffusion.cpp"
revision=290148d2e31ca6ef9757d567905776b5f3646db5
if [[ ! -d "$dependency_path/.git" ]]; then
    git clone --no-checkout https://github.com/leejet/stable-diffusion.cpp.git "$dependency_path"
elif [[ -n "$(git -C "$dependency_path" status --porcelain --untracked-files=no)" ]]; then
    echo 'Native dependency has changes; preserve them before bootstrapping.' >&2
    exit 1
fi
git -C "$dependency_path" fetch origin "$revision" --depth 1
git -C "$dependency_path" checkout --detach "$revision"
git -C "$dependency_path" submodule update --init --depth 1 ggml
