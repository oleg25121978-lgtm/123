#!/usr/bin/env python3
"""Patch binary AndroidManifest.xml WITHOUT apktool or resource table rebuilding.

Intended for the user's own Yandex Maps 31.1.5 mod APK. Retains original
resources and architecture-specific native libraries. Produces an UNSIGNED
APK which must be zipaligned and signed with Android apksigner.

Usage:
  python3 repack_maps31.py input_mod.apk maps-relay-debug.apk unsigned.apk

Never treat a successful zip operation as validation that the application
works on real devices.
"""
import argparse
import io
import re
import struct
import zipfile
from pathlib import Path

ANDROID_URI="http://schemas.android.com/apk/res/android"
PROVIDER="ru.rokidhud.mapsrelay.MapsHudProvider"
SERVICE="ru.rokidhud.mapsrelay.MapsHudKeepAliveService"
AUTHORITY="ru.yandex.yandexmaps.rokidhud.bridge"
PERMISSIONS=(
    "android.permission.BLUETOOTH_CONNECT",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE",
)
ATTRS={
    "name":0x01010003,
    "exported":0x01010010,
    "authorities":0x01010018,
    "foregroundServiceType":0x01010599,
}
def chunk(raw,pos):
    if pos+8>len(raw):raise ValueError("Truncated Android binary XML")
    typ,head,size=struct.unpack_from("<HHI",raw,pos)
    if head<8 or size<head or pos+size>len(raw):raise ValueError("Invalid AXML chunk size")
    return typ,head,size

def ulen8(b,pos):
    ch=b[pos];pos+=1
    if ch&128:return ((ch&127)<<8)|b[pos],pos+1
    return ch,pos
def ulen16(b,pos):
    ch=struct.unpack_from("<H",b,pos)[0];pos+=2
    if ch&32768:
        return ((ch&32767)<<16)|struct.unpack_from("<H",b,pos)[0],pos+2
    return ch,pos
def enc_len8(n):
    if n>=32768:raise ValueError("String too long")
    return bytes([((n>>8)&127)|128,n&255]) if n>=128 else bytes([n])
def enc_len16(n):
    if n>=32768:raise ValueError("String too long")
    return struct.pack("<HH",(n>>16)|32768,n&65535) if n>=32768 else struct.pack("<H",n)

class StringPool:
    def __init__(self,raw):
        typ,header,size=chunk(raw,0)
        if typ!=1 or header!=28:raise ValueError("Unknown Android string pool")
        self.raw=raw
        self.count,self.style_count,self.flags,self.str_start,self.style_start=struct.unpack_from("<IIIII",raw,8)
        if self.style_count:raise ValueError("Styled AndroidManifest string pool not supported; fail closed")
        self.utf8=bool(self.flags&0x100)
        self.original_data=raw[self.str_start:size]
        offsets=struct.unpack_from("<"+"I"*self.count,raw,28)
        self.values=[]
        for offset in offsets:
            p=self.str_start+offset
            if self.utf8:
                _,p=ulen8(raw,p)
                num,p=ulen8(raw,p)
                text=raw[p:p+num].decode("utf-8","replace")
            else:
                num,p=ulen16(raw,p)
                text=raw[p:p+2*num].decode("utf-16-le","replace")
            self.values.append(text)
        self.new=[]
        self.newmap={}
    def index(self,s):
        try:return self.values.index(s)
        except ValueError:pass
        if s not in self.newmap:
            self.newmap[s]=self.count+len(self.new)
            self.new.append(s)
        return self.newmap[s]
    def get(self,index):
        if index==0xFFFFFFFF:return ""
        all_strings=self.values+self.new
        if index>=len(all_strings):return ""
        return all_strings[index]
    def build(self):
        data=bytearray(self.original_data)
        offsets=list(struct.unpack_from("<"+"I"*self.count,self.raw,28))
        for string in self.new:
            offsets.append(len(data))
            if self.utf8:
                b=string.encode("utf-8")
                data+=enc_len8(len(string))+enc_len8(len(b))+b+b"\0"
            else:
                data+=enc_len16(len(string))+string.encode("utf-16-le")+b"\0\0"
        while len(data)%4:data+=b"\0"
        n=self.count+len(self.new)
        strings_start=28+4*n
        payload=struct.pack("<IIIII",n,0,self.flags,strings_start,0)
        payload+=struct.pack("<"+"I"*n,*offsets)+bytes(data)
        return struct.pack("<HHI",1,28,8+len(payload))+payload

def start_tag(pool,tag,attributes,android_index):
    attrdata=bytearray()
    for key,value,kind in sorted(attributes,key=lambda a:ATTRS[a[0]]):
        ns=android_index
        name=pool.index(key)
        if kind=="string":
            value_index=pool.index(value)
            typed=struct.pack("<HBBI",8,0,3,value_index)
            raw=value_index
        elif kind=="bool":
            typed=struct.pack("<HBBI",8,0,0x12,0xFFFFFFFF if value else 0)
            raw=0xFFFFFFFF
        elif kind=="int":
            typed=struct.pack("<HBBI",8,0,0x11,value)
            raw=0xFFFFFFFF
        else:raise ValueError(kind)
        attrdata+=struct.pack("<III",ns,name,raw)+typed
    total=36+len(attrdata)
    return struct.pack("<HHIIIIIHHHHHH",0x0102,16,total,0,0xFFFFFFFF,
        0xFFFFFFFF,pool.index(tag),20,20,len(attributes),0,0,0)+attrdata

def end_tag(pool,tag):
    return struct.pack("<HHIIIII",0x0103,16,24,0,0xFFFFFFFF,
        0xFFFFFFFF,pool.index(tag))

def attr_value(raw,pool,target):
    typ,head,size=chunk(raw,0)
    if typ!=0x0102:return None
    at,asize,acount=struct.unpack_from("<HHH",raw,24)
    start=16+at
    for i in range(acount):
        off=start+i*asize
        ns,name,raw_index=struct.unpack_from("<III",raw,off)
        if pool.get(name)==target:
            data_type=raw[off+15]
            value=struct.unpack_from("<I",raw,off+16)[0]
            if data_type==3:return pool.get(value)
            if raw_index!=0xFFFFFFFF:return pool.get(raw_index)
    return None

def patch_manifest(original):
    typ,header,length=chunk(original,0)
    if typ!=3 or header!=8 or length!=len(original):
        raise ValueError("Not standard binary AndroidManifest.xml")
    segments=[];pool=None;resource_map=None;pos=8
    while pos<len(original):
        t,h,size=chunk(original,pos)
        c=original[pos:pos+size]
        segments.append((t,c))
        if t==1:pool=StringPool(c)
        if t==0x0180:resource_map=c
        pos+=size
    if pool is None:raise ValueError("No manifest StringPool")
    android_idx=pool.index(ANDROID_URI)
    if android_idx>=pool.count:raise ValueError("Missing android XML namespace")
    tags=set()
    existing_perms=set()
    for t,c in segments:
        if t==0x0102:
            name=pool.get(struct.unpack_from("<I",c,20)[0])
            if name in ("provider","service"):
                tags.add((name,attr_value(c,pool,"name")))
            if name=="uses-permission":
                existing_perms.add(attr_value(c,pool,"name"))
    # Resolve names BEFORE building the string pool and resource map.
    for tag in ("uses-permission","provider","service"):pool.index(tag)
    for name in ATTRS:pool.index(name)
    for text in PERMISSIONS+(PROVIDER,SERVICE,AUTHORITY):pool.index(text)
    poolbytes=pool.build()
    oldids=[]
    if resource_map is not None:
        oldids=list(struct.unpack_from("<"+"I"*((len(resource_map)-8)//4),
            resource_map,8))
    n=pool.count+len(pool.new)
    ids=oldids+[0]*max(0,n-len(oldids))
    for k,v in ATTRS.items():
        ids[pool.index(k)]=v
    resbytes=struct.pack("<HHI",0x0180,8,8+4*len(ids))+struct.pack(
        "<"+"I"*len(ids),*ids)
    inserted_permissions=[]
    for perm in PERMISSIONS:
        if perm not in existing_perms:
            inserted_permissions.append(start_tag(pool,"uses-permission",
                [("name",perm,"string")],android_idx))
            inserted_permissions.append(end_tag(pool,"uses-permission"))
    inserted_components=[]
    if ("provider",PROVIDER) not in tags:
        inserted_components.append(start_tag(pool,"provider",[
            ("name",PROVIDER,"string"),
            ("authorities",AUTHORITY,"string"),
            ("exported",False,"bool")],android_idx))
        inserted_components.append(end_tag(pool,"provider"))
    if ("service",SERVICE) not in tags:
        inserted_components.append(start_tag(pool,"service",[
            ("name",SERVICE,"string"),
            ("exported",False,"bool"),
            ("foregroundServiceType",0x10,"int")],android_idx))
        inserted_components.append(end_tag(pool,"service"))
    output=[];res_written=False
    permissions_written=False;components_written=False
    for t,c in segments:
        if t==1:
            output.append(poolbytes)
        elif t==0x0180:
            output.append(resbytes);res_written=True
        else:
            if not res_written and t in (0x0100,0x0101,0x0102,0x0103):
                output.append(resbytes);res_written=True
            if t==0x0102 and pool.get(struct.unpack_from("<I",c,20)[0])=="application":
                output.extend(inserted_permissions)
                permissions_written=True
            if t==0x0103 and pool.get(struct.unpack_from("<I",c,20)[0])=="application":
                output.extend(inserted_components)
                components_written=True
            output.append(c)
    if not permissions_written or not components_written:
        raise ValueError("Application tag not found; no manifest patched")
    result=b"".join(output)
    return struct.pack("<HHI",3,8,8+len(result))+result

def get_compiler_dex(compiled):
    with zipfile.ZipFile(compiled) as apk:
        matches=[apk.read(x) for x in apk.namelist()
            if re.fullmatch(r"classes\d*\.dex",x)]
    matches=[x for x in matches if b"MapsHudProvider" in x]
    if len(matches)!=1:raise ValueError("Could not identify one MapsHudProvider DEX")
    required=(b"MapsHudKeepAliveService",b"MapsHudMiniMap",
              b"MapsHudLanes",b"MapsHudNotificationSource")
    missing=[name.decode() for name in required if name not in matches[0]]
    if missing:raise ValueError("Related classes are in another DEX: "+str(missing))
    return matches[0]

def repack(original,compiler,destination):
    dex=get_compiler_dex(compiler)
    with zipfile.ZipFile(original) as apk:
        entries=apk.infolist()
        dexes=[int(m.group(1) or 1) for item in entries
            if (m:=re.fullmatch(r"classes(\d*)\.dex",item.filename))]
        if not dexes:raise ValueError("No original DEX")
        # Previously produced Maps31 HUD variants already include classes18.dex.
        # The final signature is regenerated, not reused.
        dest_dex="classes18.dex" if max(dexes)>=18 else f"classes{max(dexes)+1}.dex"
        if dest_dex=="classes18.dex" and max(dexes)>18:
            raise ValueError("Unrecognized DEX count; refuse to overwrite user additions")
        with zipfile.ZipFile(destination,"w",allowZip64=True) as out:
            added=False
            for entry in entries:
                name=entry.filename
                if name.upper().startswith("META-INF/") and name.upper().endswith((".RSA",".DSA",".SF",".EC")):
                    continue  # old JAR signatures are invalid after modifications
                content=apk.read(name)
                if name=="AndroidManifest.xml":content=patch_manifest(content)
                elif name==dest_dex:
                    content=dex;added=True
                out.writestr(entry,content)
            if not added:out.writestr(dest_dex,dex,compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(destination) as rebuilt,zipfile.ZipFile(original) as before:
        unchanged=[n for n in before.namelist() if n not in ("AndroidManifest.xml",dest_dex)
            and not (n.upper().startswith("META-INF/") and n.upper().endswith((".RSA",".DSA",".SF",".EC")))]
        for name in unchanged:
            if rebuilt.read(name)!=before.read(name):
                raise AssertionError("Source APK entry unexpectedly modified: "+name)
    print("Rebuilt unsigned",destination,"DEX",dest_dex,"preserved",len(unchanged),"entries")

if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument("original",type=Path)
    p.add_argument("compiler",type=Path)
    p.add_argument("unsigned",type=Path)
    a=p.parse_args()
    repack(a.original,a.compiler,a.unsigned)
