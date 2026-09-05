import hashlib, sys
import xml.etree.ElementTree as ET
metadata, name, file = sys.argv[1:]
root = ET.parse(metadata).getroot()
found = None
for complete in root.iter():
    if complete.tag.split('}')[-1] != 'complete': continue
    values = {e.tag.split('}')[-1]: e for e in complete}
    if values.get('url') is not None and values['url'].text == name:
        found = values.get('checksum'); break
if found is None:
    raise SystemExit('This pinned SDK archive is absent from current metadata. Update to a reviewed official archive and checksum; do not skip verification.')
algorithm = found.attrib.get('type', 'sha1').replace('-', '').lower()
h = hashlib.new(algorithm)
with open(file, 'rb') as f:
    for chunk in iter(lambda: f.read(1048576), b''): h.update(chunk)
if h.hexdigest().lower() != found.text.strip().lower(): raise SystemExit('SDK archive checksum mismatch')
print('SDK archive verified against HTTPS repository metadata')
