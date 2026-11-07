"""Permit only exact Omni public presentation URLs, never native/runtime settings."""
BRANDING_URLS = {
    '"$GITHUB_URL/imgutss/Omni-App"': '"$GITHUB_URL/imgutss/omni-android"',
    '"https://api.github.com/repos/imgutss/Omni-App/releases"': '"https://api.github.com/repos/imgutss/omni-android/releases"',
    '"$GITHUB_RAW_URL/imgutss/Omni-App/main/docs/PRIVACY.md"': '"$GITHUB_RAW_URL/2dust/v2rayNG/master/CR.md"',
}

def same_native_app_config(baseline, current):
    for new, old in BRANDING_URLS.items():
        current = current.replace(new, old)
    return baseline == current
