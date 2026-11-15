"""Fetch pinned Android browser transports into build output; retain all bundled notices."""
import hashlib
import pathlib
import sys
import tarfile
import urllib.request

VERSION = "2.3.1"
ARCHIVES = {
    "arm64-v8a": ("aarch64", "fd8a9ba3386d10f8519fcff3b50f655a41fd38e5e5a2fb8eabcac6085587bcbf"),
    "armeabi-v7a": ("armv7", "a3ca8c80d74fcc6ba4dd92bedb0c9a82bb2246806e5116294196351cfd8fbede"),
}

def prepare(output):
    output = pathlib.Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    for abi, (triple, expected) in ARCHIVES.items():
        name = f"libcurl-impersonate-v{VERSION}.{triple}-linux-android.tar.gz"
        archive = output / name
        if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest() != expected:
            with urllib.request.urlopen(f"https://github.com/lexiforest/curl-impersonate/releases/download/v{VERSION}/{name}", timeout=60) as response:
                data = response.read(40 * 1024 * 1024 + 1)
            if len(data) > 40 * 1024 * 1024 or hashlib.sha256(data).hexdigest() != expected:
                raise RuntimeError("Browser transport archive verification failed")
            archive.write_bytes(data)
        directory = output / abi
        directory.mkdir(exist_ok=True)
        with tarfile.open(archive) as bundle:
            for entry in bundle.getmembers():
                if not (entry.name.startswith("include/") or entry.name == "libcurl-impersonate.so" or entry.name.startswith("LICENSE")):
                    continue
                target = (directory / entry.name).resolve()
                if not target.is_relative_to(directory) or entry.issym() or entry.islnk():
                    raise RuntimeError("Invalid archive path")
                bundle.extract(entry, directory, filter="data")
                if entry.name.startswith("LICENSE") and entry.isfile():
                    notices = output / "licenses" / "curl-impersonate"
                    notices.mkdir(parents=True, exist_ok=True)
                    (notices / entry.name).write_bytes(target.read_bytes())

if __name__ == "__main__":
    prepare(sys.argv[1])
