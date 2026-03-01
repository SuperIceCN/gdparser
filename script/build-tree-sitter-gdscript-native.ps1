param(
    [string]$GrammarDir = "",
    [string]$OutputRoot = "",
    [string[]]$Targets = @("windows-x86_64", "linux-x86_64", "macos-x86_64", "macos-aarch64"),
    [string]$VendorRepo = "https://github.com/PrestonKnopp/tree-sitter-gdscript.git",
    [string]$VendorRef = "v6.1.0",
    [switch]$SkipVendorUpdate
)

$ErrorActionPreference = "Stop"

function Invoke-CheckedCommand {
    param(
        [scriptblock]$Command,
        [string]$FailedMessage
    )
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw $FailedMessage
    }
}

function Update-VendorGrammar {
    param(
        [string]$Dir,
        [string]$Repo,
        [string]$Ref
    )

    if (-not (Get-Command git -ErrorAction SilentlyContinue)) {
        throw "git was not found in PATH"
    }

    if (-not (Test-Path $Dir)) {
        $parentDir = Split-Path -Parent $Dir
        New-Item -ItemType Directory -Path $parentDir -Force | Out-Null
        Write-Host "Cloning vendor grammar: $Repo -> $Dir"
        Invoke-CheckedCommand -Command { git clone $Repo $Dir } -FailedMessage "Failed to clone vendor repository"
    }

    Write-Host "Updating vendor grammar in $Dir (ref: $Ref)"
    Invoke-CheckedCommand -Command { git -C $Dir fetch --tags --force origin } -FailedMessage "Failed to fetch vendor repository"
    Invoke-CheckedCommand -Command { git -C $Dir checkout --force $Ref } -FailedMessage "Failed to checkout vendor ref: $Ref"
}

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
if ([string]::IsNullOrWhiteSpace($GrammarDir)) {
    $GrammarDir = Join-Path $repoRoot "vendor/tree-sitter-gdscript"
}
if ([string]::IsNullOrWhiteSpace($OutputRoot)) {
    $OutputRoot = Join-Path $repoRoot "native"
}

if (-not $SkipVendorUpdate.IsPresent) {
    Update-VendorGrammar -Dir $GrammarDir -Repo $VendorRepo -Ref $VendorRef
}

$grammarPath = Resolve-Path $GrammarDir -ErrorAction SilentlyContinue
if (-not $grammarPath) {
    throw "Grammar directory does not exist: $GrammarDir"
}

$outRootPath = Resolve-Path $OutputRoot -ErrorAction SilentlyContinue
if (-not $outRootPath) {
    New-Item -ItemType Directory -Path $OutputRoot -Force | Out-Null
    $outRootPath = Resolve-Path $OutputRoot
}

if (-not (Get-Command zig -ErrorAction SilentlyContinue)) {
    throw "zig was not found in PATH"
}

$parserC = Join-Path $grammarPath "src/parser.c"
$scannerC = Join-Path $grammarPath "src/scanner.c"
if (-not (Test-Path $parserC)) {
    throw "Missing parser file: $parserC"
}
if (-not (Test-Path $scannerC)) {
    throw "Missing scanner file: $scannerC"
}

$targetsMap = @{
    "windows-x86_64" = @{ ZigTarget = "x86_64-windows-gnu"; OutputFile = "tree-sitter-gdscript.dll" }
    "linux-x86_64"   = @{ ZigTarget = "x86_64-linux-gnu"; OutputFile = "libtree-sitter-gdscript.so" }
    "linux-aarch64"  = @{ ZigTarget = "aarch64-linux-gnu"; OutputFile = "libtree-sitter-gdscript.so" }
    "macos-x86_64"   = @{ ZigTarget = "x86_64-macos-none"; OutputFile = "libtree-sitter-gdscript.dylib" }
    "macos-aarch64"  = @{ ZigTarget = "aarch64-macos-none"; OutputFile = "libtree-sitter-gdscript.dylib" }
}

foreach ($target in $Targets) {
    if (-not $targetsMap.ContainsKey($target)) {
        throw "Unsupported target: $target"
    }

    $config = $targetsMap[$target]
    $targetOutDir = Join-Path $outRootPath $target
    New-Item -ItemType Directory -Path $targetOutDir -Force | Out-Null
    $outputLib = Join-Path $targetOutDir $config.OutputFile

    Write-Host "Building $target -> $outputLib"

    & zig cc `
        -O2 `
        -shared `
        -fPIC `
        -target $config.ZigTarget `
        -I (Join-Path $grammarPath "src") `
        -o $outputLib `
        $parserC `
        $scannerC

    if ($LASTEXITCODE -ne 0) {
        throw "zig cc failed for target: $target"
    }
}

Write-Host "Build finished. Output root: $outRootPath"
