#!/usr/bin/env python3
"""Build and package a continuously signed release from committed sources."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

ROOT = Path(__file__).resolve().parents[1]
CERTIFICATE = 'ff3177d13d36bdcee6abf4d8cab0804fd886a8a15a7fe56ad9712b6c286822cc'


def run(*args, capture=False):
    return subprocess.run([str(value) for value in args], cwd=ROOT, check=True,
                          text=True, capture_output=capture)


def main():
    if not os.environ.get('SFB_SIGNING_PROPERTIES'):
        raise SystemExit('Set SFB_SIGNING_PROPERTIES to the private UTF-8 signing properties file')
    run('git', 'diff', '--exit-code', 'HEAD')
    if run('git', 'ls-files', '--others', '--exclude-standard', capture=True).stdout.strip():
        raise SystemExit('Commit release sources first')
    run('./gradlew', ':app:testDebugUnitTest', ':app:assembleRelease', ':app:lintRelease')
    tools = Path(os.environ.get('ANDROID_HOME', Path.home() / 'Library/Android/sdk')) / 'build-tools/37.0.0'
    output = ROOT / 'app/build/outputs/apk/release'
    metadata = json.loads((output / 'output-metadata.json').read_text())
    entry = metadata['elements'][0]
    assert metadata['variantName'] == 'release'
    version = entry['versionName']
    apk = output / entry['outputFile']
    badging = run(tools / 'aapt2', 'dump', 'badging', apk, capture=True).stdout
    assert "name='dev.ryuya.securefolderbridge'" in badging
    assert "targetSdkVersion:'37'" in badging and 'application-debuggable' not in badging
    signature = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk, capture=True).stdout
    assert CERTIFICATE in signature
    run(tools / 'zipalign', '-c', '-P', '16', '4', apk)
    destination = ROOT / 'artifacts/release' / version
    destination.mkdir(parents=True, exist_ok=True)
    signed = destination / f'SecureFolderBridge-{version}-release.apk'
    shutil.copy2(apk, signed)
    source = destination / f'SecureFolderBridge-{version}-source.zip'
    run('git', 'archive', '--format=zip', f'--prefix=SecureFolderBridge-{version}/',
        f'--output={source}', 'HEAD')
    (destination / 'verification.txt').write_text(signature + '\nRelease variant; SDK 37; 16 KiB aligned.\n')
    release = {'schema': 1, 'packageName': 'dev.ryuya.securefolderbridge',
               'versionCode': entry['versionCode'], 'versionName': version, 'apk': signed.name,
               'sha256': hashlib.sha256(signed.read_bytes()).hexdigest()}
    (destination / 'release-metadata.json').write_text(json.dumps(release, indent=2) + '\n')
    files = [signed, source, destination / 'verification.txt', destination / 'release-metadata.json']
    (destination / 'SHA256SUMS.txt').write_text(''.join(
        hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.name + '\n' for path in files))
    print(signed)


if __name__ == '__main__':
    main()
