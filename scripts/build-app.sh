#!/bin/zsh
set -euo pipefail
cd "${0:A:h}/.."
swift build -c release
app="$PWD/dist/Pnale.app"
mkdir -p "$app/Contents/MacOS" "$app/Contents/Resources"
cp .build/release/Pnale "$app/Contents/MacOS/Pnale.next"
mv -f "$app/Contents/MacOS/Pnale.next" "$app/Contents/MacOS/Pnale"
cat > "$app/Contents/Info.plist" <<'PLIST'
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>CFBundleExecutable</key><string>Pnale</string>
<key>CFBundleIdentifier</key><string>app.pnale.ticker</string>
<key>CFBundleName</key><string>Pnale</string>
<key>CFBundleDisplayName</key><string>Pnale</string>
<key>CFBundlePackageType</key><string>APPL</string>
<key>CFBundleShortVersionString</key><string>0.3.1</string>
<key>CFBundleVersion</key><string>4</string>
<key>LSMinimumSystemVersion</key><string>14.0</string>
<key>LSUIElement</key><true/>
<key>NSHighResolutionCapable</key><true/>
</dict></plist>
PLIST
codesign --force --sign - "$app"
printf 'Built: %s\n' "$app"
