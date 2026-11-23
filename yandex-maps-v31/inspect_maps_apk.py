#!/usr/bin/env python3
"""Read-only symbol survey for Yandex Maps APK or APKM/XAPK; no patching.
    python inspect_maps_apk.py maps_31.1.5.apkm > results.json
Strings in an APK are NOT proof of a callable navigation API.
"""
import argparse
import hashlib
import io
import json
import re
import struct
import zipfile
from pathlib import Path

TOKENS = {
    "guidance": ["guidance","guidancelistener","navigationwindshield","navigationlayer"],
    "maneuver": ["maneuver","manoeuvre","turntype","turnarrow"],
    "lanes": ["lane","lanearrow","lanesign"],
    "speed": ["speedlimit","speedkm","speedometer"],
    "eta": ["arrivaltime","estimatedarrival","traveltime","routeeta"],
    "route": ["routeprogress","routesummary","remainingdistance","routegeometry","routepolyline"]
}

def uleb(data, pos):
    n=0
    for shift in (0,7,14,21,28):
        if pos>=len(data): raise ValueError("truncated ULEB128")
        c=data[pos]; pos+=1
        n |= (c&127)<<shift
        if c<128: return n,pos
    raise ValueError("invalid ULEB128")

def strings(data):
    if len(data)<112 or data[:4]!=b"dex\n":
        raise ValueError("Not DEX")
    count,offset=struct.unpack_from("<II",data,56)
    if count>10000000 or offset+4*count>len(data): raise ValueError("bad DEX table")
    for i in range(count):
        at=struct.unpack_from("<I",data,offset+4*i)[0]
        if at>=len(data): continue
        try:
            _,p=uleb(data,at)
            stop=data.find(b"\0",p,min(len(data),p+8192))
            if stop>=0: yield data[p:stop].decode("utf-8","replace")
        except ValueError: continue

def nested_apks(path):
    with zipfile.ZipFile(path) as root:
        nested=[f for f in root.infolist() if f.filename.lower().endswith(".apk")]
        if not nested:
            yield path.name,root
        else:
            for f in nested:
                with zipfile.ZipFile(io.BytesIO(root.read(f))) as apk:
                    yield f.filename,apk

def report(path, max_symbols):
    buckets={name:set() for name in TOKENS}
    result={"file":path.name, "size_bytes":path.stat().st_size,
        "sha256":hashlib.sha256(path.read_bytes()).hexdigest(),
        "dex":[], "notes":["Candidate strings are not proof of active navigation callbacks."]}
    for apkn,apk in nested_apks(path):
        for name in apk.namelist():
            if not re.search(r"(^|/)classes\d*\.dex$",name): continue
            data=apk.read(name)
            result["dex"].append({"apk":apkn,"entry":name,"bytes":len(data)})
            try:
                for s in strings(data):
                    if not (3<=len(s)<=200): continue
                    if not (s.startswith("L") and "/" in s or
                            re.fullmatch(r"[A-Za-z_][A-Za-z0-9_$<>]{2,100}",s)):
                        continue
                    low=s.lower()
                    for category,terms in TOKENS.items():
                        matching=any(term in low for term in terms)
                        if category=="eta":
                            matching |= bool(re.search(
                                r"(?<![A-Z])ETA(?![A-Z])|(?<![A-Z])Eta(?![a-z])|(?<![A-Za-z])eta(?![A-Za-z])",s))
                        if matching: buckets[category].add(s)
            except ValueError as error:
                result["notes"].append(f"{apkn}:{name}: {error}")
    result["candidate_symbols"]={
        k:sorted(vals,key=lambda x:(len(x),x))[:max_symbols] for k,vals in buckets.items()}
    result["candidate_counts"]={k:len(vals) for k,vals in buckets.items()}
    return result

if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk",type=Path)
    parser.add_argument("--max",type=int,default=80)
    args=parser.parse_args()
    print(json.dumps(report(args.apk,args.max),ensure_ascii=False,indent=2))
