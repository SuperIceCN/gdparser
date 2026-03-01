#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

GRAMMAR_DIR="${GRAMMAR_DIR:-${REPO_ROOT}/vendor/tree-sitter-gdscript}"
OUTPUT_ROOT="${OUTPUT_ROOT:-${REPO_ROOT}/native}"
TARGETS="${TARGETS:-windows-x86_64,linux-x86_64,macos-x86_64,macos-aarch64}"
VENDOR_REPO="${VENDOR_REPO:-https://github.com/PrestonKnopp/tree-sitter-gdscript.git}"
VENDOR_REF="${VENDOR_REF:-v6.1.0}"
SKIP_VENDOR_UPDATE="${SKIP_VENDOR_UPDATE:-false}"

usage() {
  cat <<'EOF'
Usage:
  build-tree-sitter-gdscript-native.sh [--grammar-dir DIR] [--output-root DIR] [--targets csv]
                                       [--vendor-repo URL] [--vendor-ref REF] [--skip-vendor-update]

Options:
  --grammar-dir DIR        Grammar path (default: vendor/tree-sitter-gdscript)
  --output-root DIR        Output root (default: native)
  --targets csv            Comma-separated targets (default: windows-x86_64,linux-x86_64,macos-x86_64,macos-aarch64)
  --vendor-repo URL        Vendor git repository URL
  --vendor-ref REF         Vendor git ref (tag/branch/commit), default: v6.1.0
  --skip-vendor-update     Skip updating/cloning vendor repository
  -h, --help               Show help
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --grammar-dir)
      GRAMMAR_DIR="$2"
      shift 2
      ;;
    --output-root)
      OUTPUT_ROOT="$2"
      shift 2
      ;;
    --targets)
      TARGETS="$2"
      shift 2
      ;;
    --vendor-repo)
      VENDOR_REPO="$2"
      shift 2
      ;;
    --vendor-ref)
      VENDOR_REF="$2"
      shift 2
      ;;
    --skip-vendor-update)
      SKIP_VENDOR_UPDATE="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 1
      ;;
  esac
done

if ! command -v git >/dev/null 2>&1; then
  echo "git was not found in PATH" >&2
  exit 1
fi

if ! command -v zig >/dev/null 2>&1; then
  echo "zig was not found in PATH" >&2
  exit 1
fi

if [[ "${SKIP_VENDOR_UPDATE}" != "true" ]]; then
  if [[ ! -d "${GRAMMAR_DIR}" ]]; then
    mkdir -p "$(dirname "${GRAMMAR_DIR}")"
    echo "Cloning vendor grammar: ${VENDOR_REPO} -> ${GRAMMAR_DIR}"
    git clone "${VENDOR_REPO}" "${GRAMMAR_DIR}"
  fi

  echo "Updating vendor grammar in ${GRAMMAR_DIR} (ref: ${VENDOR_REF})"
  git -C "${GRAMMAR_DIR}" fetch --tags --force origin
  git -C "${GRAMMAR_DIR}" checkout --force "${VENDOR_REF}"
fi

PARSER_C="${GRAMMAR_DIR}/src/parser.c"
SCANNER_C="${GRAMMAR_DIR}/src/scanner.c"
if [[ ! -f "${PARSER_C}" ]]; then
  echo "Missing parser file: ${PARSER_C}" >&2
  exit 1
fi
if [[ ! -f "${SCANNER_C}" ]]; then
  echo "Missing scanner file: ${SCANNER_C}" >&2
  exit 1
fi

mkdir -p "${OUTPUT_ROOT}"

IFS=',' read -r -a TARGET_ARRAY <<< "${TARGETS}"
for TARGET in "${TARGET_ARRAY[@]}"; do
  case "${TARGET}" in
    windows-x86_64)
      ZIG_TARGET="x86_64-windows-gnu"
      OUTPUT_FILE="tree-sitter-gdscript.dll"
      ;;
    linux-x86_64)
      ZIG_TARGET="x86_64-linux-gnu"
      OUTPUT_FILE="libtree-sitter-gdscript.so"
      ;;
    linux-aarch64)
      ZIG_TARGET="aarch64-linux-gnu"
      OUTPUT_FILE="libtree-sitter-gdscript.so"
      ;;
    macos-x86_64)
      ZIG_TARGET="x86_64-macos-none"
      OUTPUT_FILE="libtree-sitter-gdscript.dylib"
      ;;
    macos-aarch64)
      ZIG_TARGET="aarch64-macos-none"
      OUTPUT_FILE="libtree-sitter-gdscript.dylib"
      ;;
    *)
      echo "Unsupported target: ${TARGET}" >&2
      exit 1
      ;;
  esac

  TARGET_DIR="${OUTPUT_ROOT}/${TARGET}"
  mkdir -p "${TARGET_DIR}"
  OUTPUT_LIB="${TARGET_DIR}/${OUTPUT_FILE}"

  echo "Building ${TARGET} -> ${OUTPUT_LIB}"
  zig cc \
    -O2 \
    -shared \
    -fPIC \
    -target "${ZIG_TARGET}" \
    -I "${GRAMMAR_DIR}/src" \
    -o "${OUTPUT_LIB}" \
    "${PARSER_C}" \
    "${SCANNER_C}"
done

echo "Build finished. Output root: ${OUTPUT_ROOT}"
