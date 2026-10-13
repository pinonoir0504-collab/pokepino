#!/usr/bin/env python3
"""One independent, read-only Pokémon Kids discovery worker per GitHub Actions VM.
Crawling is small, non-authenticated, host-limited, and checks robots.txt.
No listing from a marketplace is treated as proof of an officially released variant.
"""
import argparse
import datetime as dt
import json
import re
import time
from pathlib import Path
from urllib.parse import urljoin, urlparse, urlencode
from urllib.robotparser import RobotFileParser
from collections import defaultdict

import requests
from bs4 import BeautifulSoup

P = argparse.ArgumentParser()
P.add_argument("--worker", type=int, required=True, choices=range(10))
P.add_argument("--outdir", default="ten_pc_results")
ARGS = P.parse_args()
CUTOFF = dt.date(2026, 10, 1)
AGENT = "PokePinoCatalogAudit/1.0 (independent public-page inventory; no image rehosting)"
SESSION = requests.Session()
SESSION.headers.update({"User-Agent": AGENT, "Accept-Language": "ja,en;q=0.8"})
robots_cache = {}
last_host_access = {}
output = {"worker": ARGS.worker, "machine": "GitHub Actions ubuntu-24.04 runner", "cutoff": "2026-10-01",
          "pages": [], "series": [], "errors": [], "claims": [], "candidates": []}
visited = set()

def allowed(url):
    host = urlparse(url).netloc.lower()
    if host not in ("www.bandai.co.jp", "bandai.co.jp", "bulbapedia.bulbagarden.net",
                    "www.yubi-nin.jp", "yubi-nin.jp", "pocketmonsters.fandom.com",
                    "ja.wikid.org"):
        return False
    if host not in robots_cache:
        robot = RobotFileParser()
        robot.set_url(f"https://{host}/robots.txt")
        try:
            robot.read()
            robots_cache[host] = robot
        except Exception as ex:
            robots_cache[host] = None
            output["errors"].append({"url": f"https://{host}/robots.txt", "error": str(ex)})
    robot = robots_cache[host]
    return robot is None or robot.can_fetch(AGENT, url)

def get(url):
    if url in visited:
        return None
    visited.add(url)
    if not allowed(url):
        output["errors"].append({"url":url,"error":"robots.txt disallows or unapproved host"})
        return None
    host = urlparse(url).netloc
    delta = time.monotonic() - last_host_access.get(host, -999)
    if delta < 0.5:
        time.sleep(0.5 - delta)
    last_host_access[host] = time.monotonic()
    try:
        response = SESSION.get(url, timeout=18)
        response.raise_for_status()
        if len(response.content) > 4_000_000:
            raise ValueError("page size cap exceeded")
        soup = BeautifulSoup(response.content, "html.parser")
        output["pages"].append({"url":url,"status":response.status_code,"title":(soup.title.get_text(" ",strip=True) if soup.title else "")[:170]})
        return soup
    except Exception as ex:
        output["errors"].append({"url":url,"error":str(ex)[:280]})
        return None

def norm(text):
    return re.sub(r"\s+"," ",str(text)).strip()

def get_month_date(title, body, url):
    # Crucial: a related-product widget includes old dates; NEVER use its first date.
    # The product's title supplies the correct month even for a future release.
    import unicodedata
    for text in (title, body[:1600]):
        text=unicodedata.normalize("NFKC",text)
        match=re.search(r"(?:発売日\s*[:：]?\s*)?((?:19|20)\d\d)[年./-](\d{1,2})(?:[月./-](\d{1,2}))?",text)
        if match:
            year,month,day=map(int,[match.group(1),match.group(2),match.group(3) or "1"])
            try:
                return dt.date(year,month,day).isoformat()
            except ValueError:
                pass
    year = re.search(r"/(19\d\d|20\d\d)/",url)
    return year.group(1) if year else "0000"

def parse_bandai(url, soup):
    # Prefer the product content, but support legacy pages as well.
    body = soup.get_text("\n", strip=True)
    title = norm(soup.title.get_text(" ",strip=True) if soup.title else "")
    date = get_month_date(title, body, url)
    if date > CUTOFF.isoformat():
        output["claims"].append({"source":url,"claim":"excluded_future","date":date})
        return
    match = re.search(r"^([^\n]+)｜発売日",title)
    release = norm(match.group(1) if match else re.search(r"商品名[：:]\s*(.+)",body).group(1) if re.search(r"商品名[：:]\s*(.+)",body) else title.split("｜")[0])
    # Determine product-list block: do not misparse ads, pages or menus.
    pos = body.find("商品リスト")
    modern = False
    if pos < 0:
        for marker in ("●彩色済ソフビ人形", "彩色済ソフビ人形"):
            if marker in body:
                pos=body.index(marker)
                modern=True
                break
    if pos < 0:
        output["candidates"].append({"series":release,"date":date,"source":url,"reason":"no enumerated lineup"})
        return
    segment = body[pos:pos+6000]
    stopper = re.search(r"(?:●ガム|●チューインガム|●シール|●カード|\(C\)|©|お問い合わせ|※画像には)",segment)
    if stopper:
        segment=segment[:stopper.start()]
    variants=[]
    for line in segment.split("\n"):
        line=norm(line)
        # Unicode fullwidth normalized.
        import unicodedata
        line=unicodedata.normalize("NFKC",line)
        m=re.match(r"^(?:(?:No|NO)\.?\s*)?(\d{1,3})[.．、:]?\s*(\S.{0,69})$",line)
        if m:
            name=norm(m.group(2)).strip("・. ")
            if not name.startswith(("円","月","種","人形","カード")) and (len(name)>0):
                variants.append(name)
    # Often numbered lines are delivered in a single text element; recover from raw body.
    # Mid-2010s Bandai releases often list figures on a single line split by ・
    # instead of numbered lines, e.g. "全12種)・クズモー ・ウデッポウ ..."
    if len(variants)<3 and "・" in segment:
        listed=re.split(r"(?:[●\n](?:シール|ラムネ|ガム)|©|\(C\))",segment)[0]
        start=re.search(r"(?:全\d+種\)|全\d+種）|全\d+種類)",listed)
        if start:
            listed=listed[start.end():]
        options=[norm(x) for x in listed.split("・")]
        clean=[x for x in options if x and len(x)<80 and not
              re.search(r"全\d+種|ソフビ人形|チューインガム|シール\d|ラムネ菓子|※",x)]
        if len(clean)>len(variants):
            variants=clean
    variants=list(dict.fromkeys(variants))
    expected=None
    m=re.search(r"(?:全|人形全)(\d{1,2})種類",body[:3000])
    if m:
        expected=int(m.group(1))
    if not variants:
        output["candidates"].append({"series":release,"date":date,"source":url,"reason":"zero parsed figures", "expected":expected})
        return
    output["series"].append({"series":release,"releaseDate":date,"source":url,"sourceGrade":"official",
        "items":variants,"expected":expected,"parseCount":len(variants),"confidence":"count_match" if expected==len(variants) else "needs_review"})

def parse_bulbapedia(soup,low,high):
    # Every heading has a nearby Released paragraph and a per-figure table.
    for heading in soup.find_all(["h4","h5"]):
        title=norm(heading.get_text(" ",strip=True))
        p=heading.find_next("p")
        if not p: continue
        intro=p.get_text(" ",strip=True)[:400]
        date_match=re.search(r"Released:\s*(.+?)(?:Box Art:|Contents|$)",intro)
        if not date_match:continue
        import dateutil.parser
        try:
            date=dateutil.parser.parse(date_match.group(1),fuzzy=True,default=dt.datetime(1900,1,1)).date()
        except Exception:
            continue
        if not low<=date.year<=high or date>CUTOFF:
            continue
        # Limit sibling search to the next heading; skip unrelated listing tables.
        table=None
        for sibling in heading.next_siblings:
            if getattr(sibling,"name",None) in ("h2","h3","h4","h5"):break
            if getattr(sibling,"name",None)=="table":
                headers=[norm(x.get_text(" ",strip=True)) for x in sibling.find_all("th")]
                if "Pokémon" in headers or "Pokemon" in headers:
                    table=sibling
                    break
        if not table: continue
        headers=[norm(x.get_text(" ",strip=True)) for x in table.find_all("th")]
        col=headers.index("Pokémon") if "Pokémon" in headers else headers.index("Pokemon")
        figures=[]
        for tr in table.find_all("tr"):
            tds=tr.find_all("td",recursive=False)
            if len(tds)<=col:continue
            index=norm(tds[0].get_text(" ",strip=True))
            pokemon=norm(tds[col].get_text(" ",strip=True))
            if not pokemon or not re.match(r"^[\dA-Z]{2,6}$",index):continue
            # Original English display name is retained; not silently equated to JP figure variant.
            flags={headers[i]:norm(td.get_text(" ",strip=True))[:80] for i,td in enumerate(tds) if i<len(headers) and i!=col}
            figures.append({"number":index,"name":pokemon,"flags":flags})
        if figures:
            output["series"].append({"series":title,"releaseDate":date.isoformat(),"source":"https://bulbapedia.bulbagarden.net/wiki/Pok%C3%A9mon_Kids",
               "sourceGrade":"wiki_secondary","items":[x["name"] for x in figures],
               "figures":figures,"expected":len(figures),"parseCount":len(figures),"confidence":"wiki_unverified"})

def wiki_candidates(soup, low, high, url):
    for table in soup.find_all("table"):
        rows=table.find_all("tr")
        if not rows or len(rows)>150:continue
        for tr in rows[1:]:
            cells=[norm(t.get_text(" ",strip=True)) for t in tr.find_all(["td","th"])]
            if not cells:continue
            row=" | ".join(cells)
            for year in range(low,high+1):
                if str(year) in row and "ポケモンキッズ" in row:
                    output["candidates"].append({"series":row[:230],"date":str(year),"source":url,"reason":"independent chronology"})

def brand_pages(offsets,year_min=1996,year_max=2026):
    for offset in offsets:
        url="https://www.bandai.co.jp/candy/brand/brand88/index.html?"+urlencode({"__limit":20,"__offset":offset,"__order":"sale_start_date_year:desc, sale_start_date_month:desc, sale_start_date_week:desc, sale_start_date_day:desc"})
        s=get(url)
        if s is None:continue
        links={}
        for a in s.find_all("a",href=True):
            href=urljoin(url,a["href"])
            if re.search(r"/candy/products/(?:19|20)\d{2}/[^/?#]+\.html",href):
                links[href]=norm(a.get_text(" ",strip=True))
        for link,label in links.items():
            output["candidates"].append({"series":label,"source":link,"reason":"brand_index","offset":offset})
            m=re.search(r"/(19\d\d|20\d\d)/",link)
            if not m or not year_min<=int(m.group(1))<=year_max:continue
            detail=get(link)
            if detail is not None:parse_bandai(link,detail)

def old_bandai_urls(yearlo,yearhi):
    base=Path(__file__).resolve().parents[1]/"app/src/main/assets"
    old=json.loads((base/"historical_releases_1996_2023.json").read_text(encoding="utf-8"))
    for entry in old["series"]:
        year=int(entry["releaseDate"][:4])
        if yearlo<=year<=yearhi and "www.bandai.co.jp" in entry["source"]:
            soup=get(entry["source"])
            if soup:parse_bandai(entry["source"],soup)

def yubi_inventory(minyear,maxyear):
    # Independent specialist archive and its contiguous linked pages; no image scraping.
    targets=[
       "https://www.yubi-nin.jp/pokemon/poke_fukkoku/poke_THE.html",
       "https://www.yubi-nin.jp/pokemon/_pokeALL/pokemon_ALL00.html",
    ]
    for url in targets:
        soup=get(url)
        if not soup:continue
        for a in soup.find_all("a",href=True):
            link=urljoin(url,a["href"])
            caption=norm(a.get_text(" ",strip=True))
            if "yubi-nin.jp/pokemon" not in link or not ("kids" in link.lower() or "poke_" in link.lower()):continue
            if re.search(r"(19\d\d|20\d\d)",caption) and not any(str(y) in caption for y in range(minyear,maxyear+1)):continue
            output["candidates"].append({"series":caption[:180],"source":link,"reason":"specialist_archive_link"})

# Independent assignments. Some third-party hosts prohibit bot crawling:
# for those, use Bandai-origin alternate tracks rather than bypass restrictions.
id=ARGS.worker
if id==0:brand_pages([0,20],2019,2026)
elif id==1:brand_pages([40,60,80],2010,2018)
elif id==2:old_bandai_urls(1996,2000)
elif id==3:old_bandai_urls(2001,2006)
elif id==4:brand_pages([40,60,80],2010,2013)
elif id==5:brand_pages([40,60,80],2014,2017)
elif id==6:yubi_inventory(1996,2005)
elif id==7:yubi_inventory(2006,2026)
elif id==8:old_bandai_urls(1996,2005)
elif id==9:old_bandai_urls(2006,2023)

# Remove duplicate source/series reports within a worker.
dedupe=set()
found=[]
for series in output["series"]:
    key=(series["source"],series["series"],series["releaseDate"])
    if key in dedupe:continue
    dedupe.add(key);found.append(series)
output["series"]=found
out=Path(ARGS.outdir)
out.mkdir(exist_ok=True,parents=True)
(out/f"worker_{id}.json").write_text(json.dumps(output,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps({"worker":id,"pages":len(output["pages"]),"series":len(found),"figures":sum(len(s["items"]) for s in found),
                  "candidates":len(output["candidates"]),"errors":len(output["errors"])},ensure_ascii=False))
