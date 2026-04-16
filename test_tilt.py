import ssl

f = r'E:\Workspace\Wallpaper\app\src\main\res\layout\studio_page_date.xml'
import os
with open(f, 'r', encoding='utf-8') as fstream:
    if 'TILT' in fstream.read():
        with open('test_tilt.txt', 'w') as dest:
            dest.write('It works!')
