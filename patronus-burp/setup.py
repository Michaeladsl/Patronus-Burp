#!/usr/bin/env python3
"""
setup.py - Patronus Burp Extension build setup
Works on Windows, macOS, and Linux.

Usage:
    python3 setup.py        (macOS/Linux)
    python setup.py         (Windows)

What it does:
    1. Checks for Java 17+ (and tells you exactly how to get it if missing)
    2. Downloads gradle-wrapper.jar
    3. Runs the build
    4. Installs Flask web server dependencies
    5. Tells you where the final JAR is and how to use everything
"""

import os
import sys
import platform
import subprocess
import urllib.request
import shutil
from pathlib import Path

# -- Colours (disabled on Windows unless ANSICON/WT) --
IS_WIN = platform.system() == "Windows"

def c(text, code):
    if IS_WIN:
        return text
    return f"\033[{code}m{text}\033[0m"

def green(t):  return c(t, "32")
def yellow(t): return c(t, "33")
def red(t):    return c(t, "31")
def cyan(t):   return c(t, "36")
def gray(t):   return c(t, "90")

SCRIPT_DIR = Path(__file__).parent.resolve()
WRAPPER_JAR = SCRIPT_DIR / "gradle" / "wrapper" / "gradle-wrapper.jar"
GRADLEW     = SCRIPT_DIR / ("gradlew.bat" if IS_WIN else "gradlew")
JAR_OUT     = SCRIPT_DIR / "build" / "libs" / "patronus-burp.jar"

WRAPPER_JAR_URL = (
    "https://raw.githubusercontent.com/gradle/gradle"
    "/v8.5.0/gradle/wrapper/gradle-wrapper.jar"
)

def banner():
    print()
    print(yellow("  Patronus Burp - Cross-Platform Build Setup"))
    print(yellow("  ============================================"))
    print()

def step(n, total, msg):
    print(cyan(f"[{n}/{total}] {msg}"))

def ok(msg):
    print(green(f"      OK: {msg}"))

def info(msg):
    print(gray(f"      {msg}"))

def fail(msg):
    print(red(f"\n  ERROR: {msg}"))

def die(msg, hint=None):
    fail(msg)
    if hint:
        print()
        for line in hint.strip().splitlines():
            print(f"  {line}")
    print()
    sys.exit(1)

# -- Step 1: Check Java --
TOTAL_STEPS = 4
REQUIREMENTS_TXT = SCRIPT_DIR / "server" / "requirements.txt"

def check_java():
    step(1, TOTAL_STEPS, "Checking Java 17+...")

    java_cmd = shutil.which("java")
    if not java_cmd:
        # Check if JAVA_HOME is set even if not on PATH
        java_home = os.environ.get("JAVA_HOME")
        if java_home:
            candidate = Path(java_home) / "bin" / ("java.exe" if IS_WIN else "java")
            if candidate.exists():
                java_cmd = str(candidate)

    if not java_cmd:
        die(
            "Java not found on PATH.",
            java_install_hint()
        )

    try:
        result = subprocess.run(
            [java_cmd, "-version"],
            capture_output=True, text=True
        )
        # java -version prints to stderr
        version_output = result.stderr or result.stdout
    except Exception as e:
        die(f"Could not run java: {e}", java_install_hint())

    # Parse major version from e.g. 'java version "17.0.9"' or 'openjdk version "21.0.1"'
    major = None
    for line in version_output.splitlines():
        if "version" in line.lower():
            # Extract the quoted version string
            parts = line.split('"')
            if len(parts) >= 2:
                ver_str = parts[1]
                # Handle old-style "1.8.0" and new-style "17.0.9"
                first = ver_str.split(".")[0]
                second = ver_str.split(".")[1] if "." in ver_str else "0"
                if first == "1":
                    major = int(second)  # 1.8 -> 8
                else:
                    try:
                        major = int(first)
                    except ValueError:
                        pass
            break

    if major is None:
        die(
            f"Could not parse Java version from:\n      {version_output.strip()}",
            java_install_hint()
        )

    if major < 17:
        die(
            f"Java {major} found but Java 17+ is required.\n"
            f"      (java.com only offers Java 8 which is outdated - do not use it)",
            java_install_hint()
        )

    ok(f"Java {major} found at: {java_cmd}")
    return java_cmd


def java_install_hint():
    system = platform.system()
    if system == "Darwin":
        return """
Install Java 21 (recommended):

    Option A - Homebrew (easiest):
        brew install temurin21

    Option B - Download installer:
        https://adoptium.net/temurin/releases/?version=21
        Choose: macOS | x64 or aarch64 (M1/M2) | pkg
"""
    elif system == "Windows":
        return """
Install Java 21 (recommended):

    1. Go to: https://adoptium.net/temurin/releases/?version=21
    2. Choose: Windows | x64 | msi
    3. Run the installer (it sets JAVA_HOME automatically)
    4. Open a NEW terminal window and run this script again

    Note: java.com only offers Java 8 which is too old. Use adoptium.net instead.
"""
    else:
        return """
Install Java 21 (recommended):

    Ubuntu/Debian:
        sudo apt install temurin-21-jdk
        (add adoptium repo first: https://adoptium.net/installation/linux/)

    Or download from: https://adoptium.net/temurin/releases/?version=21
"""


# -- Step 2: Download gradle-wrapper.jar --
def download_wrapper():
    step(2, TOTAL_STEPS, "Checking Gradle wrapper JAR...")

    if WRAPPER_JAR.exists():
        ok("gradle-wrapper.jar already present")
        return

    WRAPPER_JAR.parent.mkdir(parents=True, exist_ok=True)
    info(f"Downloading from GitHub...")
    info(f"URL: {WRAPPER_JAR_URL}")

    try:
        def progress(block, block_size, total):
            if total > 0:
                pct = min(100, block * block_size * 100 // total)
                print(f"\r      Progress: {pct}%", end="", flush=True)

        urllib.request.urlretrieve(WRAPPER_JAR_URL, WRAPPER_JAR, reporthook=progress)
        print()  # newline after progress
        ok(f"Saved to: {WRAPPER_JAR}")
    except Exception as e:
        die(
            f"Download failed: {e}",
            f"""
Manual fix:
    Download: {WRAPPER_JAR_URL}
    Save to:  {WRAPPER_JAR}
"""
        )


# -- Step 3: Build --
def build():
    step(3, TOTAL_STEPS, "Building patronus-burp.jar...")
    print()
    info("First run downloads Gradle (~130MB) - this is normal and only happens once.")
    info("Subsequent builds are fast.")
    print()

    if not GRADLEW.exists():
        die(
            f"Gradle wrapper not found at: {GRADLEW}",
            "Make sure you extracted the full ZIP and are running from inside the patronus-burp folder."
        )

    # Make gradlew executable on Unix
    if not IS_WIN:
        GRADLEW.chmod(GRADLEW.stat().st_mode | 0o111)

    cmd = [str(GRADLEW), "shadowJar"]

    try:
        result = subprocess.run(cmd, cwd=SCRIPT_DIR)
    except Exception as e:
        die(f"Failed to run Gradle: {e}")

    if result.returncode != 0:
        print()
        print(red("  BUILD FAILED"))
        print()
        print("  Common fixes:")
        print("  - Check your internet connection (Gradle downloads Kotlin + dependencies)")
        print(f"  - Try manually: {GRADLEW} shadowJar --info")
        print("  - Make sure Java 17+ is the active Java (not Java 8)")
        print()
        sys.exit(1)

    print()
    print(green("  BUILD SUCCESSFUL"))
    print()


# -- Step 4: Install Flask dependencies --
def install_server_deps():
    step(4, TOTAL_STEPS, "Installing web server dependencies...")

    if not REQUIREMENTS_TXT.exists():
        fail(f"server/requirements.txt not found at: {REQUIREMENTS_TXT}")
        info("Skipping - you can install manually later: pip install flask")
        return

    try:
        result = subprocess.run(
            [sys.executable, "-m", "pip", "install", "-r", str(REQUIREMENTS_TXT)],
            cwd=SCRIPT_DIR,
            capture_output=True, text=True
        )
        if result.returncode == 0:
            ok("Flask and dependencies installed")
        else:
            fail("pip install failed:")
            for line in (result.stderr or result.stdout).strip().splitlines()[-5:]:
                info(line)
            info("You can install manually later: pip install flask")
    except Exception as e:
        fail(f"Could not run pip: {e}")
        info("You can install manually later: pip install flask")


def print_summary():
    server_py = SCRIPT_DIR / "server" / "patronus_server.py"
    print()
    print(green("  ============================================"))
    print(green("  SETUP COMPLETE"))
    print(green("  ============================================"))
    print()
    print(yellow("  STEP 1 — Load extension into Burp Suite:"))
    print("  1. Open Burp Suite")
    print("  2. Extensions -> Add -> Extension type: Java")
    print(f"  3. Select: {JAR_OUT}")
    print("  4. Click Next — a Patronus tab appears in Burp navigation")
    print("  5. Set your engagement name and use Burp normally")
    print()
    print(yellow("  STEP 2 — Launch the web server (review, playlist, recording):"))
    if IS_WIN:
        print(f"  python \"{server_py}\"")
    else:
        print(f"  python3 \"{server_py}\"")
    print("  Then open: http://localhost:5001")
    print()
    print(yellow("  How it works:"))
    print("  - Burp captures traffic → exports JSON to ~/.patronus/burp_sessions/")
    print("  - The web server watches that folder and displays sessions automatically")
    print("  - Use the Playlist builder to select, preview, annotate, and record findings")
    print()


# -- Main --
if __name__ == "__main__":
    banner()
    check_java()
    download_wrapper()
    build()
    install_server_deps()
    print_summary()
