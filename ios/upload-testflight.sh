#!/usr/bin/env bash
set -euo pipefail

# Values come only from GitHub Secrets; never enable shell tracing here.
for name in IOS_TEAM_ID IOS_DISTRIBUTION_P12_BASE64 IOS_DISTRIBUTION_PASSWORD IOS_PROFILE_BASE64 ASC_KEY_ID ASC_ISSUER_ID ASC_PRIVATE_KEY; do
  if [[ -z "${!name:-}" ]]; then echo "Missing GitHub Secret: $name" >&2; exit 1; fi
done
[[ "${IOS_BUILD_NUMBER:-}" =~ ^[1-9][0-9]{0,8}$ ]] || { echo 'A positive build number is required.' >&2; exit 1; }
[[ "$IOS_TEAM_ID" =~ ^[A-Z0-9]{10}$ ]] || { echo 'Invalid Apple team ID.' >&2; exit 1; }
[[ "$ASC_KEY_ID" =~ ^[A-Z0-9]{10}$ ]] || { echo 'Invalid App Store Connect key ID.' >&2; exit 1; }
[[ "$ASC_ISSUER_ID" =~ ^[a-fA-F0-9-]{36}$ ]] || { echo 'Invalid App Store Connect issuer ID.' >&2; exit 1; }
[[ "$(uname -s)" == Darwin && -n "${RUNNER_TEMP:-}" ]] || { echo 'Use the macOS GitHub workflow.' >&2; exit 1; }

node ios/validate-metadata.mjs --draft
node ios/generate-project.mjs

signingDirectory="$(mktemp -d "$RUNNER_TEMP/rideledger-signing.XXXXXX")"
keychainFile="$signingDirectory/build.keychain-db"
profileFile=''
profileInstalled=false
cleanup() {
  security delete-keychain "$keychainFile" >/dev/null 2>&1 || true
  # All sensitive files are confined to this fresh, checked runner temporary directory.
  if [[ "$signingDirectory" == "$RUNNER_TEMP"/rideledger-signing.* && -d "$signingDirectory" ]]; then
    find "$signingDirectory" -type f -exec /bin/rm -f {} \;
    /bin/rmdir "$signingDirectory" 2>/dev/null || true
  fi
  if [[ "$profileInstalled" == true && "$profileFile" == "$profileDirectory"/* ]]; then /bin/rm -f "$profileFile"; fi
}
# Both paths are initialized before the trap so cleanup also works after a partial failure.
profileDirectory="$RUNNER_TEMP/rideledger-profiles"
trap cleanup EXIT
umask 077
printf '%s' "$IOS_DISTRIBUTION_P12_BASE64" | base64 --decode > "$signingDirectory/certificate.p12"
printf '%s' "$IOS_PROFILE_BASE64" | base64 --decode > "$signingDirectory/profile.mobileprovision"
printf '%s' "$ASC_PRIVATE_KEY" > "$signingDirectory/AuthKey_$ASC_KEY_ID.p8"
security cms -D -i "$signingDirectory/profile.mobileprovision" > "$signingDirectory/profile.plist"
profileID=$(/usr/libexec/PlistBuddy -c 'Print :UUID' "$signingDirectory/profile.plist")
[[ "$profileID" =~ ^[a-fA-F0-9-]{36}$ ]] || { echo 'Invalid provisioning profile UUID.' >&2; exit 1; }
profileTeam=$(/usr/libexec/PlistBuddy -c 'Print :TeamIdentifier:0' "$signingDirectory/profile.plist")
profileApp=$(/usr/libexec/PlistBuddy -c 'Print :Entitlements:application-identifier' "$signingDirectory/profile.plist")
[[ "$profileTeam" == "$IOS_TEAM_ID" && "$profileApp" == "$IOS_TEAM_ID.com.eurty.rideledger" ]] || {
  echo 'Provisioning profile must match your team and com.eurty.rideledger.' >&2; exit 1;
}
if /usr/libexec/PlistBuddy -c 'Print :ProvisionedDevices' "$signingDirectory/profile.plist" >/dev/null 2>&1; then
  echo 'Use an App Store Connect distribution profile, not development or ad hoc.' >&2; exit 1
fi

keychainPassword="$(openssl rand -base64 32)"
printf '::add-mask::%s\n' "$keychainPassword"
security create-keychain -p "$keychainPassword" "$keychainFile"
security set-keychain-settings -lut 21600 "$keychainFile"
security unlock-keychain -p "$keychainPassword" "$keychainFile"
security import "$signingDirectory/certificate.p12" -P "$IOS_DISTRIBUTION_PASSWORD" -A -t cert -f pkcs12 -k "$keychainFile" >/dev/null
security set-key-partition-list -S apple-tool:,apple:,codesign: -s -k "$keychainPassword" "$keychainFile" >/dev/null
security list-keychains -d user -s "$keychainFile"
identity="$(security find-identity -v -p codesigning "$keychainFile" | awk '/Apple Distribution/{print $2; exit}')"
[[ "$identity" =~ ^[A-Fa-f0-9]{40}$ ]] || { echo 'No Apple Distribution identity with a private key was imported.' >&2; exit 1; }

# Xcode 16+ stores profiles under this per-user directory. Copy only our own UUID.
profileDirectory="$HOME/Library/Developer/Xcode/UserData/Provisioning Profiles"
mkdir -p "$profileDirectory"
profileFile="$profileDirectory/$profileID.mobileprovision"
[[ ! -e "$profileFile" ]] || { echo 'Refusing to replace an existing runner profile.' >&2; exit 1; }
cp "$signingDirectory/profile.mobileprovision" "$profileFile"
profileInstalled=true

xcodebuild archive -project ios/RideLedger.xcodeproj -scheme RideLedger -configuration Release \
  -destination 'generic/platform=iOS' -archivePath ios/build/RideLedger.xcarchive \
  CURRENT_PROJECT_VERSION="$IOS_BUILD_NUMBER" CODE_SIGN_STYLE=Manual \
  DEVELOPMENT_TEAM="$IOS_TEAM_ID" CODE_SIGN_IDENTITY="$identity" PROVISIONING_PROFILE_SPECIFIER="$profileID"

export IOS_PROFILE_UUID="$profileID" IOS_SIGNING_IDENTITY="$identity" IOS_EXPORT_OPTIONS="$signingDirectory/ExportOptions.plist"
python3 <<'PY'
import os, plistlib
with open(os.environ['IOS_EXPORT_OPTIONS'], 'wb') as output:
    plistlib.dump({'method':'app-store-connect','signingStyle':'manual','teamID':os.environ['IOS_TEAM_ID'],
        'signingCertificate':os.environ['IOS_SIGNING_IDENTITY'],
        'provisioningProfiles':{'com.eurty.rideledger':os.environ['IOS_PROFILE_UUID']},
        'manageAppVersionAndBuildNumber':False,'uploadSymbols':True}, output)
PY
xcodebuild -exportArchive -archivePath ios/build/RideLedger.xcarchive -exportPath ios/build/export \
  -exportOptionsPlist "$signingDirectory/ExportOptions.plist"
export API_PRIVATE_KEYS_DIR="$signingDirectory"
xcrun altool --upload-app --type ios --file ios/build/export/RideLedger.ipa --apiKey "$ASC_KEY_ID" --apiIssuer "$ASC_ISSUER_ID"
echo 'Upload completed. Wait for Apple processing, then complete export compliance and TestFlight tester/review settings in App Store Connect.'
