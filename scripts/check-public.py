#!/usr/bin/env python3
"""Audit the exact staged files and all local commit history before publishing."""
import hashlib, pathlib, re, subprocess, sys
ROOT = pathlib.Path(__file__).resolve().parents[1]
# Explicit reviewed allowlist; additions require deliberate review.
ALLOWED = {'.gitattributes', 'app/src/main/java/cn/wavenote/demo/DemoAudioLibrary.kt', 'gradle.properties', 'gradle/wrapper/gradle-wrapper.properties', 'app/src/main/java/cn/wavenote/demo/DemoController.kt', 'build.gradle.kts', 'gradle/wrapper/gradle-wrapper.jar', 'app/src/main/java/cn/wavenote/demo/DemoAudioStore.kt', 'app/src/test/java/cn/wavenote/demo/DemoAudioTest.kt', 'app/src/test/java/cn/wavenote/demo/DemoFlowTest.kt', 'app/src/test/java/cn/wavenote/demo/IdentityHTTPTest.kt', 'gradlew', 'scripts/run.sh', 'app/src/main/java/cn/wavenote/demo/KotlinIntegration.kt', 'app/src/main/java/cn/wavenote/demo/DemoNativePlayer.kt', 'app/src/main/res/xml/data_extraction_rules.xml', 'app/src/main/java/cn/wavenote/demo/JavaIntegration.java', 'gradlew.bat', 'app/src/main/AndroidManifest.xml', 'README.md', 'app/build.gradle.kts', 'scripts/check-public.py', 'app/src/main/java/cn/wavenote/demo/IdentityProvider.kt', 'scripts/verify.sh', 'scripts/prepare-sdk.sh', '.gitignore', 'app/src/main/java/cn/wavenote/demo/IdentityHTTP.kt', 'settings.gradle.kts', 'app/src/main/res/values/styles.xml', 'app/src/main/java/cn/wavenote/demo/MainActivity.kt', 'app/src/main/java/cn/wavenote/demo/DemoFlow.kt'}
WRAPPER_SHA256 = '7d3a4ac4de1c32b59bc6a4eb8ecb8e612ccd0cf1ae1e99f66902da64df296172'
def git(*args):
    return subprocess.check_output(['git', '-C', str(ROOT), *args])
def fail(message):
    raise SystemExit('PUBLIC CHECK FAILED: ' + message)
def check(name, mode, data):
    if name not in ALLOWED: fail('unapproved path: ' + name)
    if mode not in ['100644', '100755']: fail('unsupported file mode: ' + name)
    if name == 'gradle/wrapper/gradle-wrapper.jar':
        if hashlib.sha256(data).hexdigest() != WRAPPER_SHA256: fail('Gradle wrapper checksum changed')
        return
    try: text = data.decode('utf-8')
    except UnicodeDecodeError: fail('unexpected binary: ' + name)
    forbidden = [
        r'/(?:Users|home)/[A-Za-z0-9_.-]+/',
        r'\b(?:192\.168|10\.\d+)\.\d+\.\d+\b',
        r'\bgh[pousr]_[A-Za-z0-9]{20,}\b',
        r'github_pat_[A-Za-z0-9_]{20,}',
        r'BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY',
        r'DEVELOPMENT_TEAM\s*=\s*(?!""\s*;)[A-Za-z0-9]+\s*;',
        r'yjking10/wavenote-sdk(?:\.git|/|[)\s])',
        'geili' + 'jiyao',
    ]
    if any(re.search(pattern, text) for pattern in forbidden): fail('private content pattern: ' + name)
    if name.endswith('.md'):
        for link in re.findall(r'(?<!!)\[[^\]]*\]\(([^)]+)\)', re.sub(r'```.*?```', '', text, flags=re.S)):
            target=link.split('#')[0].strip('<>')
            if not target or re.match(r'\w+://|mailto:',target):continue
            resolved=(ROOT/name).parent/target
            try: relative=str(resolved.resolve().relative_to(ROOT))
            except ValueError: fail('documentation link escapes repository: '+name)
            if relative not in ALLOWED:fail('link targets unpublished file: '+name)
# Index inspection ensures ignored files cannot be smuggled in with git add -f.
for row in git('ls-files', '--stage', '-z').decode().split('\0'):
    if not row: continue
    metadata,name=row.split('\t',1);mode,oid,stage=metadata.split()
    if stage != '0':fail('unmerged index: '+name)
    check(name,mode,git('cat-file','blob',oid))
for name in filter(None,git('ls-files','--others','--exclude-standard','-z').decode().split('\0')):
    if name not in ALLOWED:fail('unreviewed untracked file: '+name)
commits=git('rev-list','--all').decode().splitlines()
seen=set()
for commit in commits:
    for row in git('ls-tree','-r','-z',commit).decode().split('\0'):
        if not row:continue
        metadata,name=row.split('\t',1);mode,kind,oid=metadata.split()
        if (name,oid) in seen:continue
        seen.add((name,oid))
        if kind!='blob':fail('nested repository not allowed: '+name)
        check(name,mode,git('cat-file','blob',oid))
    for email in git('show','-s','--format=%ae%n%ce',commit).decode().splitlines():
        if not email.endswith('@users.noreply.github.com'):fail('commit contains non-noreply email')
print('PASS public file allowlist, staged blobs, links, wrapper checksum and full commit history')
