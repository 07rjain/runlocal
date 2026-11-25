#!/usr/bin/env python3
"""Print a catalog entry for an exact public Hugging Face model file."""

import argparse
import hashlib
import json
import re
import sys
from urllib.parse import quote
from urllib.request import urlopen


def pin(args):
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,95}/[A-Za-z0-9][A-Za-z0-9._-]{0,95}", args.repo):
        raise ValueError("Use owner/repository for the Hugging Face repo.")
    if args.repo.split("/")[0] in {"api", "spaces", "datasets"}:
        raise ValueError("Use a Hugging Face model repository.")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", args.revision):
        raise ValueError("Use a Hugging Face branch, tag or commit as the revision.")
    if len(args.file) > 512 or any(part in {"", ".", ".."} for part in args.file.split("/")) or re.search(r"[\\\x00-\x1f\x7f-\x9f]", args.file):
        raise ValueError("Use the exact relative path to the file in its repository.")

    url = f"https://huggingface.co/api/models/{args.repo}/revision/{quote(args.revision, safe='')}?blobs=true"
    with urlopen(url, timeout=15) as response:
        payload = response.read(8 * 1024 * 1024 + 1)
    if len(payload) > 8 * 1024 * 1024:
        raise ValueError("Hugging Face returned too much metadata.")
    metadata = json.loads(payload)
    if metadata.get("private") is not False or metadata.get("gated") is not False:
        raise ValueError("Choose a public, ungated repository.")
    file = next((item for item in metadata.get("siblings", []) if item["rfilename"] == args.file), None)
    if file is None:
        raise ValueError("The file was absent from the repository at this revision.")
    lfs = file.get("lfs", {})
    sha = lfs.get("sha256", "")
    size = lfs.get("size", file.get("size", 0))
    revision = metadata.get("sha", "")
    if not re.fullmatch(r"[0-9a-f]{40}", revision) or not isinstance(size, int) or not 0 < size <= 64 * 1024**3:
        raise ValueError("Could not find a pinned file with a publisher SHA-256 and download size.")
    if not sha and isinstance(file.get("blobId"), str) and size <= 8 * 1024**2:
        pinned_url = f"https://huggingface.co/{args.repo}/resolve/{revision}/{quote(args.file, safe='/')}"
        with urlopen(pinned_url, timeout=15) as response:
            body = response.read(size + 1)
        blob = hashlib.sha1(f"blob {size}\0".encode() + body).hexdigest()
        if len(body) != size or blob != file["blobId"]:
            raise ValueError("The file did not match its pinned Git blob.")
        sha = hashlib.sha256(body).hexdigest()
    if not re.fullmatch(r"[0-9a-f]{64}", sha):
        raise ValueError("Could not establish the file's SHA-256.")
    return dict(repo=args.repo, revision=revision, path=args.file, bytes=size, sha256=sha), metadata.get("cardData") or {}


def entry(args):
    if not args.id or not re.fullmatch(r"[a-z0-9][a-z0-9.-]{0,63}", args.id):
        raise ValueError("Use a lowercase --id with letters, digits, periods and hyphens.")
    if args.runtime == "litert-lm":
        if not args.file.endswith(".litertlm") or re.search(r"mediatek|qualcomm|npu|hexagon|exynos", args.file, re.I):
            raise ValueError("Choose a generic .litertlm export.")
    elif args.runtime == "llama-gguf-v1":
        if args.gpu or not args.file.endswith(".gguf") or re.search(r"mmproj|-[0-9]{5}-of-[0-9]{5}", args.file, re.I):
            raise ValueError("Choose one complete instruct GGUF for llama.cpp CPU.")
    elif args.gpu or not args.file.endswith((".gguf", ".safetensors")):
        raise ValueError("Choose a complete checkpoint for the selected Stable Diffusion family.")

    fields, card = pin(args)
    license_name = args.license or card.get("license")
    if not isinstance(license_name, str) or license_name in {"", "other", "unknown"}:
        raise ValueError("Read the model's license and provide its name with --license.")
    for label, value, limit in (("title", args.title, 80), ("summary", args.summary, 240), ("license", license_name, 120)):
        if not value or not value.strip() or len(value) > limit or re.search(r"[\x00-\x1f\x7f-\x9f]", value):
            raise ValueError(f"Use a single line of up to {limit} characters for {label}.")
    model = dict(id=args.id, runtime=args.runtime, title=args.title, summary=args.summary, license=license_name)
    fields["file"] = fields.pop("path")
    model.update(fields)
    if args.runtime in {"llama-gguf-v1", "sdxl-v1"}:
        model["runtime"] = "builtin"
        model["adapter"] = args.runtime
    if args.gpu:
        model["gpu"] = True
    if args.experimental:
        model["experimental"] = True
    return model


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo", help="Hugging Face owner/repository")
    parser.add_argument("file", help="Exact path in the Hugging Face repository")
    parser.add_argument("--id")
    parser.add_argument("--title")
    parser.add_argument("--summary")
    parser.add_argument("--pin-only", action="store_true", help="Print one pinned file object for a multi-file adapter recipe")
    parser.add_argument("--license", help="License name if the publisher's metadata is incomplete")
    parser.add_argument("--revision", default="main", help="Hugging Face branch, tag or commit to resolve")
    parser.add_argument("--runtime", choices=("litert-lm", "sd15", "llama-gguf-v1", "sdxl-v1"), default="litert-lm")
    parser.add_argument("--gpu", action="store_true")
    parser.add_argument("--experimental", action="store_true")
    try:
        args = parser.parse_args()
        print(json.dumps(pin(args)[0] if args.pin_only else entry(args), indent=2, ensure_ascii=False))
    except (ValueError, OSError) as error:
        print(f"Could not create catalog entry: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
