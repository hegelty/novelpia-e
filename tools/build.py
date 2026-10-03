#!/usr/bin/env python3
"""Small reproducible Linux build, reuses an installed Android SDK (Windows SDK OK).

Requires JDK17+, Python3, unzip. Dependencies are pinned and SHA-256 checked.
No Gradle installation or modification of the shared SDK is needed.
"""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DEPS = {
    "aapt2.jar": ("https://dl.google.com/dl/android/maven2/com/android/tools/build/aapt2/8.7.3-12006047/aapt2-8.7.3-12006047-linux.jar",
                  "028916a040b58f34c9c89984e1bc48f8da6581662014f2b66391860def8b9c36"),
    "conscrypt.aar": ("https://repo.maven.apache.org/maven2/org/conscrypt/conscrypt-android/2.5.2/conscrypt-android-2.5.2.aar",
                      "42d18979caf53f5ef68548c76d4c98b41adb910a32ad9448133f9c5b20bd65a3"),
    "jsoup.jar": ("https://repo.maven.apache.org/maven2/org/jsoup/jsoup/1.15.4/jsoup-1.15.4.jar",
                  "006830d0797710408de5589c18d864343c0ef1a3a0c603c9cb138c943b67d9a4"),
    "junit.jar": ("https://repo.maven.apache.org/maven2/junit/junit/4.13.2/junit-4.13.2.jar",
                  "8e495b634469d64fb8acfa3495a065cbacc8a0fff55ce1e31007be4c16dc57d3"),
    "hamcrest.jar": ("https://repo.maven.apache.org/maven2/org/hamcrest/hamcrest-core/1.3/hamcrest-core-1.3.jar",
                     "66fdef91e9739348df7a096aa384a5685f4e875584cce89386a7a47251c4d8e9"),
    "json-test.jar": ("https://repo.maven.apache.org/maven2/org/json/json/20140107/json-20140107.jar",
                      "8e5aa0a368bee60347b5a4ad861d9f68c7793f60deeea89efd449eb70d5ae622"),
}

VALIDATION_APP_ID = "me.crema.novelia.validation"
VALIDATION_SMOKE_ID = "me.crema.novelia.validation.smoke"


def _write_manifest(out, root, name):
    """Write an ElementTree manifest to a temporary file under the run outdir."""
    path = out / name
    ET.ElementTree(root).write(str(path), encoding="utf-8", xml_declaration=True)
    return path


def _manifest_root(package):
    return ET.Element("manifest", {
        "xmlns:android": "http://schemas.android.com/apk/res/android",
        "package": package,
    })


def generate_validation_app_manifest(out):
    """Isolated validation app manifest (temporary file, original untouched)."""
    root = _manifest_root(VALIDATION_APP_ID)
    ET.SubElement(root, "uses-sdk", {"android:minSdkVersion": "19",
                                     "android:targetSdkVersion": "28"})
    ET.SubElement(root, "uses-permission",
                  {"android:name": "android.permission.INTERNET"})
    application = ET.SubElement(root, "application", {
        "android:label": "노벨피아e · 검증",
        "android:icon": "@mipmap/ic_launcher",
        "android:theme": "@style/AppTheme",
        "android:allowBackup": "false",
        "android:hardwareAccelerated": "false",
        "android:usesCleartextTraffic": "false",
        "android:supportsRtl": "true",
    })
    activity = ET.SubElement(application, "activity", {
        # Class package stays me.crema.novelia; only the manifest package
        # (applicationId) changes so the validation app runs on an isolated
        # UID/data namespace without touching the real app.
        "android:name": "me.crema.novelia.MainActivity",
        "android:exported": "true",
    })
    intent_filter = ET.SubElement(activity, "intent-filter")
    ET.SubElement(intent_filter, "action",
                  {"android:name": "android.intent.action.MAIN"})
    ET.SubElement(intent_filter, "category",
                  {"android:name": "android.intent.category.LAUNCHER"})
    return _write_manifest(out, root, "AndroidManifest.xml")


def generate_validation_smoke_manifest(out):
    """Isolated validation smoke manifest (temporary file, original untouched)."""
    root = _manifest_root(VALIDATION_SMOKE_ID)
    ET.SubElement(root, "uses-sdk", {"android:minSdkVersion": "19",
                                     "android:targetSdkVersion": "28"})
    ET.SubElement(root, "uses-permission",
                  {"android:name": "android.permission.INTERNET"})
    ET.SubElement(root, "application", {"android:label": "Novelia smoke"})
    ET.SubElement(root, "instrumentation", {
        # Instrumentation class package stays me.crema.novelia; only the target
        # package points at the isolated validation applicationId.
        "android:name": "me.crema.novelia.SmokeInstrumentation",
        "android:targetPackage": VALIDATION_APP_ID,
        "android:label": "API 19 smoke",
    })
    return _write_manifest(out, root, "smoke-AndroidManifest.xml")


def run(*args):
    print("+", " ".join(map(str, args)), flush=True)
    subprocess.run(list(map(str, args)), cwd=ROOT, check=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", default=os.environ.get("ANDROID_HOME"))
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--deps", type=Path, default=ROOT / ".tools/deps")
    parser.add_argument("--api19-jar", type=Path, help="Compile against API19 for compatibility checking")
    parser.add_argument("--smoke", action="store_true", help="Also build the separate API19 smoke-test APK")
    parser.add_argument("--isolated-smoke", action="store_true",
                        help="Build an isolated validation app/smoke pair under dist/validation/ "
                             "(requires --smoke; same sources, different applicationId, no data overlap)")
    args = parser.parse_args()
    if args.isolated_smoke and not args.smoke:
        parser.error("--isolated-smoke requires --smoke")
    if not args.sdk:
        parser.error("--sdk or ANDROID_HOME required")
    sdk = Path(args.sdk)
    java = Path(args.java_home) / "bin" if args.java_home else Path(shutil.which("java")).parent
    platforms = sorted(sdk.glob("platforms/android-*/android.jar"), reverse=True)
    tools = sorted(sdk.glob("build-tools/*/lib/d8.jar"), reverse=True)
    if not platforms or not tools:
        parser.error("SDK must have an Android platform and build-tools (35/36 recommended)")
    android = next((p for p in platforms if p.parent.name == "android-36"), platforms[0])
    buildtools = tools[0].parent
    deps = args.deps.resolve()
    deps.mkdir(parents=True, exist_ok=True)
    for name, (url, digest) in DEPS.items():
        dest = deps / name
        if not dest.exists():
            print("Downloading", name, flush=True)
            urllib.request.urlretrieve(url, dest)
        if hashlib.sha256(dest.read_bytes()).hexdigest() != digest:
            raise SystemExit("Checksum mismatch: " + str(dest))
    for name, folder in (("aapt2.jar", "aapt2"), ("conscrypt.aar", "conscrypt")):
        with zipfile.ZipFile(deps / name) as z:
            for item in z.infolist():
                target = deps / folder / item.filename
                if item.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                elif not target.exists() or target.read_bytes() != z.read(item):
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(z.read(item))
    aapt = deps / "aapt2/aapt2"
    aapt.chmod(0o755)
    # A unique intermediate directory avoids including stale class files on rebuild.
    import tempfile
    outroot = ROOT / "build/manual"
    outroot.mkdir(parents=True, exist_ok=True)
    out = Path(tempfile.mkdtemp(prefix="run-", dir=outroot))
    classes, gen, dex = out / "classes", out / "gen", out / "dex"
    for directory in (classes, gen, dex):
        directory.mkdir()
    run(aapt, "compile", "--dir", ROOT / "app/src/main/res", "-o", out / "resources.zip")
    validation = args.isolated_smoke
    if validation:
        generated_manifests = [generate_validation_app_manifest(out),
                               generate_validation_smoke_manifest(out)]
        app_manifest = generated_manifests[0]
        # Application package changes but the sources still reference R from
        # their original me.crema.novelia package (no R imports anywhere), so
        # keep the generated R package identical via aapt2 --custom-package.
        app_link_extra = ["--custom-package", "me.crema.novelia"]
    else:
        generated_manifests = []
        app_manifest = ROOT / "app/src/main/AndroidManifest.xml"
        app_link_extra = []
    run(aapt, "link", "-I", android, "--manifest", app_manifest,
        "--min-sdk-version", "19", "--target-sdk-version", "28",
        "--version-code", "2", "--version-name", "0.1.0",
        "--java", gen, "-A", ROOT / "app/src/main/assets", *app_link_extra,
        "-o", out / "unsigned.apk", out / "resources.zip")
    libraries = [deps / "conscrypt/classes.jar", deps / "jsoup.jar"]
    cp = os.pathsep.join(map(str, [args.api19_jar or android] + libraries))
    sources = sorted((ROOT / "app/src/main/java").rglob("*.java")) + list(gen.rglob("*.java"))
    bootcp = args.api19_jar or android
    # Java 8 lambdas (MainActivity) need the build-tools lambda bootstrap stubs
    # on the *classpath*; the Android jar on the bootclasspath supplies all
    # platform classes (`--release 8` would hide them).
    lambda_stubs = buildtools.parent / "core-lambda-stubs.jar"
    run(java / "javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
        "-bootclasspath", bootcp,
        "-cp", os.pathsep.join(map(str, [lambda_stubs] + libraries)),
        "-d", classes, *sources)
    run(java / "jar", "cf", out / "app.jar", "-C", classes, ".")
    run(java / "java", "-cp", buildtools / "d8.jar", "com.android.tools.r8.D8",
        "--min-api", "19", "--lib", android, "--output", dex, out / "app.jar", *libraries)
    with zipfile.ZipFile(out / "unsigned.apk", "a", zipfile.ZIP_DEFLATED) as apk:
        for file in dex.glob("*.dex"):
            apk.write(file, file.name)
        for file in (deps / "conscrypt/jni").rglob("*.so"):
            apk.write(file, "lib/" + str(file.relative_to(deps / "conscrypt/jni")))
    key = ROOT / ".tools/debug.keystore"
    key.parent.mkdir(exist_ok=True)
    if not key.exists():
        run(java / "keytool", "-genkeypair", "-keystore", key, "-storepass", "android",
            "-keypass", "android", "-alias", "androiddebugkey", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "10000", "-dname", "CN=Novelia Debug,O=Local,C=KR")
    dist = ROOT / "dist"
    dist.mkdir(exist_ok=True)
    if validation:
        dist = dist / "validation"
        dist.mkdir(exist_ok=True)
    apk = dist / ("novelpia-e-0.1.0-validation.apk" if validation
                  else "novelpia-e-0.1.0.apk")
    run(java / "java", "-jar", buildtools / "apksigner.jar", "sign",
        "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android",
        "--v1-signing-enabled", "true", "--v2-signing-enabled", "true",
        "--out", apk, out / "unsigned.apk")
    run(java / "java", "-jar", buildtools / "apksigner.jar", "verify",
        "--verbose", "--min-sdk-version", "19", apk)
    testclasses = out / "tests"
    testclasses.mkdir()
    # android.jar contains stub junit.* classes; real JUnit must take precedence.
    testcp = os.pathsep.join(map(str, [classes, deps / "junit.jar", deps / "hamcrest.jar",
                                     deps / "json-test.jar"]
                                   + libraries + [args.api19_jar or android]))
    tests = sorted((ROOT / "app/src/test/java").rglob("*.java"))
    run(java / "javac", "--release", "8", "-encoding", "UTF-8", "-cp", testcp,
        "-d", testclasses, *tests)
    names = [str(t.relative_to(ROOT / "app/src/test/java")).replace("/", ".")[:-5]
             for t in tests if t.name.endswith("Test.java")]
    if args.smoke:
        smokeclasses, smokedex = out / "smokeclasses", out / "smokedex"
        smokeclasses.mkdir()
        smokedex.mkdir()
        run(java / "javac", "-encoding", "UTF-8", "-source", "8", "-target", "8",
            "-bootclasspath", bootcp, "-cp", os.pathsep.join(map(str, [classes, lambda_stubs] + libraries)),
            "-d", smokeclasses, *sorted((ROOT / "app/src/androidTest/java").rglob("*.java")))
        run(java / "jar", "cf", out / "smoke.jar", "-C", smokeclasses, ".")
        run(java / "java", "-cp", buildtools / "d8.jar", "com.android.tools.r8.D8",
            "--min-api", "19", "--lib", android, "--classpath", out / "app.jar",
            "--output", smokedex, out / "smoke.jar")
        if validation:
            smoke_manifest = generated_manifests[1]
        else:
            smoke_manifest = ROOT / "app/src/androidTest/AndroidManifest.xml"
        run(aapt, "link", "-I", android, "--manifest", smoke_manifest,
            "--min-sdk-version", "19", "-o", out / "smoke-unsigned.apk")
        with zipfile.ZipFile(out / "smoke-unsigned.apk", "a", zipfile.ZIP_DEFLATED) as testapk:
            for file in smokedex.glob("*.dex"):
                testapk.write(file, file.name)
        smoke_apk = dist / ("novelpia-e-0.1.0-validation-smoke.apk" if validation
                            else "novelpia-e-0.1.0-smoke.apk")
        run(java / "java", "-jar", buildtools / "apksigner.jar", "sign",
            "--ks", key, "--ks-pass", "pass:android", "--key-pass", "pass:android",
            "--v1-signing-enabled", "true", "--out", smoke_apk, out / "smoke-unsigned.apk")
        if validation:
            print(f"Built {smoke_apk} ({smoke_apk.stat().st_size / 1024:.1f} KiB)")
    run(java / "java", "-cp", str(testclasses) + os.pathsep + testcp,
        "org.junit.runner.JUnitCore", *names)
    digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    (dist / (apk.name + ".sha256")).write_text(digest + "  " + apk.name + "\n")
    print(f"Built {apk} ({apk.stat().st_size / 1024 / 1024:.2f} MiB), SHA256 {digest}")


if __name__ == "__main__":
    main()
