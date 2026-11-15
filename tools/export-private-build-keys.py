"""Export existing desktop provider settings into a private, temporary build input."""
import argparse
import os
from pathlib import Path
import sqlite3


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--tv-backend", help="Reachable LAN Mainapi URL for this private TV build")
    args = parser.parse_args()
    output = args.output.resolve()
    checkout = Path(__file__).resolve().parent.parent
    if output == checkout or checkout in output.parents:
        parser.error("Private keys must be written outside the source checkout.")
    database = Path(os.environ["APPDATA"]) / "SokoulPC" / "sokoul.db"
    allowed = {"tmdb", "realdebrid", "omdb", "fanart", "tvdb", "tvmaze", "watchmode", "tastedive", "motn", "opensubtitles", "subdl"}
    with sqlite3.connect(database.as_uri() + "?mode=ro", uri=True) as connection:
        values = {key.removeprefix("api_key_"): value.strip()
                  for key, value in connection.execute("SELECT key,value FROM settings WHERE key LIKE 'api_key_%'")
                  if key.removeprefix("api_key_") in allowed and value.strip()}
    if not values.get("tmdb"):
        parser.error("Existing desktop settings have no TMDB key; configure it before packaging.")
    if args.tv_backend:
        from urllib.parse import urlsplit
        parsed = urlsplit(args.tv_backend)
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password:
            parser.error("Supply a valid Mainapi URL without credentials.")
        key = Path(os.environ["LOCALAPPDATA"]) / "SokoulApi" / "access.key"
        values["mainapi"] = key.read_text(encoding="utf-8").strip()
        values["mainapi_url"] = args.tv_backend.rstrip("/")
    def escape(value):
        return "".join(("\\u%04x" % ord(char)) if ord(char) > 126 else
                       {"\\": "\\\\", "\n": "\\n", "\r": "\\r", "\t": "\\t", "=": "\\=", ":": "\\:", " ": "\\ "}.get(char, char)
                       for char in value)
    output.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(output, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(descriptor, "w", encoding="ascii", newline="\n") as target:
        for key, value in sorted(values.items()):
            target.write(f"sokoul.key.{key}={escape(value)}\n")
    print(f"Exported {len(values)} existing provider settings. Values remain hidden.")


if __name__ == "__main__":
    try:
        main()
    except (OSError, sqlite3.Error):
        raise SystemExit("Private settings could not be exported. Check source access and destination permissions.")
