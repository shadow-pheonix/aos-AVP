#!/usr/bin/env python3
"""Configure release signing without committing or publishing a private key."""
import base64
import os
from pathlib import Path
import subprocess

root = Path(__file__).resolve().parents[2]
key = Path(os.environ['RUNNER_TEMP']) / 'nova-tv-release.keystore'
configured = os.environ.get('NOVA_RELEASE_KEYSTORE_BASE64', '')
store_password = os.environ.get('NOVA_RELEASE_STORE_PASSWORD', '')
key_password = os.environ.get('NOVA_RELEASE_KEY_PASSWORD', '')
alias = os.environ.get('NOVA_RELEASE_KEY_ALIAS', '')

if not configured:
    raise SystemExit('Existing release key required: configure NOVA_RELEASE_KEYSTORE_BASE64 or map the existing secret name in this workflow.')
if not store_password or not alias:
    raise SystemExit('Configured signing requires NOVA_RELEASE_STORE_PASSWORD and NOVA_RELEASE_KEY_ALIAS.')
key_password = key_password or store_password
key.write_bytes(base64.b64decode(''.join(configured.split()), validate=True))
mode = 'Existing release signing key from GitHub Actions secrets'
env = dict(os.environ, SIGN_STORE_PASSWORD=store_password, SIGN_KEY_PASSWORD=key_password)

key.chmod(0o600)

def escape(value):
    out = ''
    for c in str(value):
        if c in '\\ =:#!':
            out += '\\' + c
        elif not 32 <= ord(c) <= 126:
            encoded = c.encode('utf-16-be')
            out += ''.join('\\u' + encoded[i:i+2].hex() for i in range(0, len(encoded), 2))
        else:
            out += c
    return out

properties = root / 'Video/keystore.properties'
properties.write_text(''.join(f'{k}={escape(v)}\n' for k, v in {
    'storeFile': key, 'storePassword': store_password,
    'keyAlias': alias, 'keyPassword': key_password,
}.items()), encoding='ascii')
properties.chmod(0o600)
artifacts = root / 'artifacts'
artifacts.mkdir(exist_ok=True)
(artifacts / 'signing-info.txt').write_text(mode + '\n')
with (artifacts / 'release-signing-certificate.pem').open('w') as certificate:
    subprocess.run(['keytool', '-exportcert', '-rfc', '-keystore', str(key),
                    '-alias', alias, '-storepass:env', 'SIGN_STORE_PASSWORD'],
                   env=env, stdout=certificate, check=True)
print(mode)
