f = r'E:\Workspace\Wallpaper\app\src\main\res\layout\studio_page_typography.xml'

with open(f, 'r', encoding='utf-8') as fstream:
    if 'MINUTE COLOR' in fstream.read():
        with open('test_minute.txt', 'w') as dest:
            dest.write('It works!')
