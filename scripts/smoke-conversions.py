#!/usr/bin/env python3
"""Exercise all 15 deployed conversion routes using fixtures exported by integration tests.
CONVERSION_SMOKE_FIXTURES=/tmp/converter-smoke-fixtures sh mvnw verify
python3 scripts/smoke-conversions.py http://localhost:8080 /tmp/converter-smoke-fixtures
Use an isolated test database and sufficient guest quota. No third-party Python packages.
"""
import io, json, pathlib, sys, urllib.request, uuid, zipfile, gzip
base=sys.argv[1].rstrip('/')
fixtures=pathlib.Path(sys.argv[2])
routes={
 'pdf-to-word':'text.pdf','word-to-pdf':'word.docx','pdf-to-excel':'text.pdf',
 'excel-to-pdf':'sheet.xlsx','ppt-to-pdf':'slides.pptx','jpg-to-png':'image.jpg',
 'png-to-jpg':'image.png','jpg-to-webp':'image.jpg','webp-to-png':'image.webp',
 'heic-to-jpg':'image.heic','mp4-to-mp3':'clip.mp4','mp4-to-gif':'clip.mp4',
 'wav-to-mp3':'audio.wav','zip-extract':'archive.zip','file-compress':'note.txt'}
for route,filename in routes.items():
 boundary='converter-smoke-'+uuid.uuid4().hex
 body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{filename}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
       +(fixtures/filename).read_bytes()+f'\r\n--{boundary}--\r\n'.encode())
 request=urllib.request.Request(base+'/api/convert/'+route,data=body,headers={'Content-Type':'multipart/form-data; boundary='+boundary})
 with urllib.request.urlopen(request,timeout=150) as response: data=json.load(response)
 assert data['status']=='success',(route,data)
 # Downloads go to the tested host, independently of its configured public hostname.
 identifier=data['downloadUrl'].rsplit('/',1)[-1]
 with urllib.request.urlopen(base+'/api/download/'+identifier,timeout=30) as response:
  assert response.headers.get('Cache-Control')=='no-store',route
  output=response.read(); mime=response.headers.get_content_type()
 assert output,(route,'empty output')
 extension=pathlib.Path(data['fileName']).suffix
 if extension=='.pdf': assert output.startswith(b'%PDF-') and mime=='application/pdf',route
 elif extension in ('.docx','.xlsx','.zip'):
  with zipfile.ZipFile(io.BytesIO(output)) as archive:
   assert archive.testzip() is None,route
   required={'.docx':'word/document.xml','.xlsx':'xl/workbook.xml','.zip':'note.txt'}[extension]
   assert required in archive.namelist(),route
 elif extension=='.gz': assert gzip.decompress(output)==b'Invoice details',route
 elif extension=='.png': assert output.startswith(b'\x89PNG\r\n\x1a\n'),route
 elif extension=='.jpg': assert output.startswith(b'\xff\xd8'),route
 elif extension=='.webp': assert output.startswith(b'RIFF') and output[8:12]==b'WEBP',route
 elif extension=='.gif': assert output.startswith((b'GIF87a',b'GIF89a')),route
 elif extension=='.mp3': assert mime=='audio/mpeg' and len(output)>1000,route
 print(f'PASS {route}: {len(output)} bytes, {mime}',flush=True)
print('All 15 conversion routes passed.')
