#!/usr/bin/env python3
"""A made-up Xtream provider for the device checks in CI (8.54).

Answers like a real panel: player_api.php (login, categories, live / films / series lists, film and
series details), xmltv.php (a guide for the live channels), the streams (/live, /movie, /series, from
test videos made with ffmpeg) and the pictures (logos, posters, backdrops made with Pillow).

  python3 server.py <media dir> [port]

The media dir holds live.ts, film.mp4, film-hevc.mkv and img/{logo,poster,back}/N.png (make_media.sh).
"""
import json
import os
import re
import sys
import time
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

MEDIA = sys.argv[1] if len(sys.argv) > 1 else "media"
PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8080
HOST = "http://10.0.2.2:%d" % PORT  # the emulator's name for this machine
PICS = 60

LIVE_GROUPS = ["##### UK #####", "UK | Entertainment", "UK | Sports", "UK | News", "UK | Kids", "UK | Movies",
               "AR | News", "AR | Sports", "AR | Drama", "AR | Kids", "AR: Movies", "FR | Cinema", "FR | Sport",
               "US | Entertainment", "US | Sports", "US | News", "DE | Sport", "IN | Hindi", "TR | Ulusal",
               "Music", "Documentary", "Radio"]
FILM_GROUPS = ["Recently added", "Action", "Comedy", "Drama", "Thriller", "Animation", "Arabic films",
               "4K UHD", "Kids", "Horror", "Sci-Fi", "Documentary"]
SERIES_GROUPS = ["Drama series", "Comedy series", "Arabic series", "Kids series", "Crime", "Netflix originals"]
TITLES = ["The Matrix", "Heat", "Inception", "Interstellar", "The Dark Knight", "Gladiator", "Alien", "Aliens",
          "Toy Story", "Toy Story 2", "Toy Story 3", "John Wick", "John Wick: Chapter 2", "Mad Max: Fury Road",
          "Dune", "Dune: Part Two", "Arrival", "Blade Runner 2049", "Up", "Coco", "Joker", "Parasite", "Whiplash",
          "Se7en", "Fight Club", "Memento", "The Prestige", "Avatar", "Titanic", "Jaws", "Rocky", "Rocky II",
          "Creed", "Top Gun: Maverick", "Oppenheimer", "Barbie", "Inside Out", "Frozen", "Moana", "Shrek",
          "Shrek 2", "The Godfather", "The Godfather Part II", "Casablanca", "Amelie", "Spirited Away",
          "Ocean's Eleven", "Ocean's Twelve", "Mission: Impossible", "Skyfall", "Spectre", "No Time to Die"]
SHOWS = ["Breaking Bad", "The Office", "Friends", "Dark", "Narcos", "The Crown", "Chernobyl", "Sherlock",
         "Bluey", "Succession", "Severance", "The Bear", "Fargo", "Mindhunter", "Ozark", "Lupin"]

now = int(time.time())


def live_streams():
    out = []
    sid = 1
    for gi, g in enumerate(LIVE_GROUPS):
        count = 4 if g.startswith("#") else 48
        for k in range(count):
            name = g if g.startswith("#") else "%s %d" % (g.split("|")[-1].split(":")[-1].strip(), k + 1)
            if "Sports" in g or "Sport" in g:
                name = ["beIN Sports %d HD", "Sky Sports %d", "SSC %d", "Eurosport %d 4K"][k % 4] % (k // 4 + 1)
            out.append({"num": sid, "name": name, "stream_type": "live", "stream_id": sid,
                        "stream_icon": "%s/img/logo/%d.png" % (HOST, sid % PICS), "epg_channel_id": "ch%d" % sid,
                        "added": str(now - sid * 600), "category_id": str(gi + 1), "tv_archive": 1 if sid % 7 == 0 else 0,
                        "tv_archive_duration": 3 if sid % 7 == 0 else 0})
            sid += 1
    return out


def films():
    out = []
    sid = 1
    for gi, g in enumerate(FILM_GROUPS):
        for k in range(90):
            t = TITLES[(gi * 7 + k) % len(TITLES)]
            year = 1975 + (gi * 13 + k * 7) % 51
            tag = ["", " 4K", " (MULTI-SUB)", "", " HDR"][k % 5] if g != "4K UHD" else " 4K"
            name = ("EN - " if k % 3 == 0 else "") + "%s (%d)%s" % (t, year, tag)
            ext = "mkv" if k % 6 == 5 else "mp4"
            out.append({"num": sid, "name": name, "stream_type": "movie", "stream_id": sid,
                        "stream_icon": "%s/img/poster/%d.png" % (HOST, sid % PICS), "rating": str(5 + (sid % 45) / 10),
                        "added": str(now - sid * 3600), "category_id": str(gi + 1), "container_extension": ext})
            sid += 1
    return out


def series_list():
    out = []
    sid = 1
    for gi, g in enumerate(SERIES_GROUPS):
        for k in range(20):
            name = "%s%s" % (SHOWS[(gi * 5 + k) % len(SHOWS)], "" if k < len(SHOWS) else " %d" % k)
            out.append({"num": sid, "name": name, "series_id": sid, "cover": "%s/img/poster/%d.png" % (HOST, (sid * 3) % PICS),
                        "plot": "A made-up series for the device check.", "rating": str(6 + (sid % 35) / 10),
                        "last_modified": str(now - sid * 7200), "category_id": str(gi + 1)})
            sid += 1
    return out


# SCALE (8.57): a big provider for timing how long a big playlist takes to open. SCALE=8 gives ~21,000
# channels in 176 groups, ~43,000 films in 96 groups and ~4,800 series in 48 groups.
SCALE = int(os.environ.get("SCALE", "1"))
if SCALE > 1:
    PREFIX = ["EN", "FR", "DE", "ES", "IT", "PT", "TR", "NL", "PL", "SE"]
    LIVE_GROUPS = LIVE_GROUPS[:1] + ["%s %s" % (PREFIX[i % len(PREFIX)], g) for i in range(SCALE) for g in LIVE_GROUPS[1:]]
    FILM_GROUPS = ["%s - %s" % (PREFIX[i % len(PREFIX)], g) for i in range(SCALE) for g in FILM_GROUPS]
    SERIES_GROUPS = ["%s - %s" % (PREFIX[i % len(PREFIX)], g) for i in range(SCALE) for g in SERIES_GROUPS]
    _films, _series = films, series_list

    def films():
        out = []
        for rep in range(5):
            for f in _films():
                f = dict(f)
                f["stream_id"] = f["num"] = f["stream_id"] + rep * 100000
                if rep:
                    f["name"] = f["name"] + " %d" % rep
                out.append(f)
        return out

    def series_list():
        out = []
        for rep in range(5):
            for x in _series():
                x = dict(x)
                x["series_id"] = x["num"] = x["series_id"] + rep * 100000
                if rep:
                    x["name"] = x["name"] + " %d" % rep
                out.append(x)
        return out

LIVE, FILMS, SERIES = live_streams(), films(), series_list()
print("catalog: %d live, %d films, %d series" % (len(LIVE), len(FILMS), len(SERIES)), flush=True)


def cats(groups):
    return [{"category_id": str(i + 1), "category_name": g, "parent_id": 0} for i, g in enumerate(groups)]


def series_info(sid):
    eps = {}
    n = 1
    for season in (1, 2):
        eps[str(season)] = [{"id": str(sid * 100 + n + e), "episode_num": e + 1, "title": "Episode %d" % (e + 1),
                             "container_extension": "mp4", "season": season,
                             "info": {"duration": "00:42:00", "movie_image": "%s/img/back/%d.png" % (HOST, (sid + e) % PICS)}}
                            for e in range(8)]
        n += 8
    s = next((x for x in SERIES if x["series_id"] == sid), SERIES[0])
    return {"info": {"name": s["name"], "cover": s["cover"], "plot": "Season after season of a made-up story, told for the device check.",
                     "cast": "Actor One, Actor Two, Actor Three", "genre": "Drama", "releaseDate": "2019-05-01", "rating": s["rating"],
                     "backdrop_path": ["%s/img/back/%d.png" % (HOST, sid % PICS)]},
            "episodes": eps}


def vod_info(sid):
    f = next((x for x in FILMS if x["stream_id"] == sid), FILMS[0])
    return {"info": {"name": f["name"], "movie_image": f["stream_icon"], "plot": "A made-up film for the device check: two people, a city, one long night.",
                     "cast": "Actor One, Actor Two", "director": "A Director", "genre": "Drama", "releasedate": "2014-11-07",
                     "duration": "02:49:00", "rating": f["rating"], "backdrop_path": ["%s/img/back/%d.png" % (HOST, sid % PICS)]},
            "movie_data": {"stream_id": sid, "container_extension": f["container_extension"]}}


def xmltv():
    start = datetime.now(timezone.utc).replace(minute=0, second=0, microsecond=0) - timedelta(hours=6)
    out = ['<?xml version="1.0" encoding="UTF-8"?>', '<tv generator-info-name="device-check">']
    shown = LIVE[:400]
    for c in shown:
        out.append('<channel id="ch%d"><display-name>%s</display-name></channel>' % (c["stream_id"], esc(c["name"])))
    teams = ["Arsenal", "Chelsea", "Liverpool", "Real Madrid", "Barcelona", "Al Hilal", "Al Ahly", "PSG"]
    for c in shown:
        t = start
        i = 0
        while t < start + timedelta(hours=30):
            sid = c["stream_id"]
            sport = "Sport" in c["name"] or "beIN" in c["name"] or "SSC" in c["name"]
            if sport and i % 3 == 1:
                title = "%s v %s" % (teams[(sid + i) % 8], teams[(sid + i + 3) % 8])
                cat = "Sports"
            elif i % 5 == 2:
                title = TITLES[(sid + i) % len(TITLES)]
                cat = "Movie"
            else:
                title = "Programme %d" % (i + 1)
                cat = "Entertainment"
            stop = t + timedelta(minutes=30 if i % 4 else 60)
            out.append('<programme start="%s" stop="%s" channel="ch%d"><title>%s</title><desc>What is on: a made-up programme for the device check.</desc><category>%s</category></programme>'
                       % (t.strftime("%Y%m%d%H%M%S +0000"), stop.strftime("%Y%m%d%H%M%S +0000"), sid, esc(title), cat))
            t = stop
            i += 1
    out.append("</tv>")
    return "\n".join(out).encode()


def esc(s):
    return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")


GUIDE = xmltv()


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        sys.stderr.write("%s %s\n" % (time.strftime("%H:%M:%S"), fmt % args))

    def send_json(self, obj):
        body = json.dumps(obj).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        u = urlparse(self.path)
        q = {k: v[0] for k, v in parse_qs(u.query).items()}
        path = u.path
        if path.endswith("player_api.php"):
            if q.get("username") != "u" or q.get("password") != "p":
                return self.send_json({"user_info": {"auth": 0}})
            a = q.get("action")
            if a is None:
                return self.send_json({"user_info": {"username": "u", "password": "p", "auth": 1, "status": "Active",
                                                     "exp_date": str(now + 86400 * 200), "max_connections": "2", "active_cons": "0"},
                                       "server_info": {"url": "10.0.2.2", "port": str(PORT), "timezone": "UTC"}})
            return self.send_json({
                "get_live_categories": lambda: cats(LIVE_GROUPS), "get_live_streams": lambda: LIVE,
                "get_vod_categories": lambda: cats(FILM_GROUPS), "get_vod_streams": lambda: FILMS,
                "get_series_categories": lambda: cats(SERIES_GROUPS), "get_series": lambda: SERIES,
                "get_series_info": lambda: series_info(int(q.get("series_id", "1"))),
                "get_vod_info": lambda: vod_info(int(q.get("vod_id", "1"))),
            }.get(a, lambda: [])())
        if path.endswith("xmltv.php"):
            return self.send_bytes(GUIDE, "application/xml")
        m = re.match(r"^/img/(logo|poster|back)/(\d+)\.png$", path)
        if m:
            return self.send_file(os.path.join(MEDIA, "img", m.group(1), m.group(2) + ".png"), "image/png")
        m = re.match(r"^/(live|movie|series)/u/p/(\d+)\.(\w+)$", path)
        if m:
            kind, sid, ext = m.group(1), int(m.group(2)), m.group(3)
            if kind == "live":
                return self.send_file(os.path.join(MEDIA, "live.ts"), "video/mp2t", ranges=False)
            name = "film-hevc.mkv" if ext == "mkv" else "film.mp4"
            return self.send_file(os.path.join(MEDIA, name), "video/x-matroska" if ext == "mkv" else "video/mp4")
        self.send_error(404)

    def send_bytes(self, body, ctype):
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def send_file(self, fn, ctype, ranges=True):
        if not os.path.exists(fn):
            return self.send_error(404)
        size = os.path.getsize(fn)
        start, end = 0, size - 1
        r = self.headers.get("Range")
        if ranges and r and r.startswith("bytes="):
            a, _, b = r[6:].partition("-")
            start = int(a) if a else 0
            end = int(b) if b else size - 1
            self.send_response(206)
            self.send_header("Content-Range", "bytes %d-%d/%d" % (start, end, size))
        else:
            self.send_response(200)
        if ranges:
            self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(end - start + 1))
        self.end_headers()
        try:
            with open(fn, "rb") as f:
                f.seek(start)
                left = end - start + 1
                while left > 0:
                    chunk = f.read(min(65536, left))
                    if not chunk:
                        break
                    self.wfile.write(chunk)
                    left -= len(chunk)
        except (BrokenPipeError, ConnectionResetError):
            pass


if __name__ == "__main__":
    print("live %d, films %d, series %d, guide %d bytes" % (len(LIVE), len(FILMS), len(SERIES), len(GUIDE)), flush=True)
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
