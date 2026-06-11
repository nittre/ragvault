target = '/app/build/index.html'
snippet = '<script src="/static/admin-menu.js" defer></script>'

content = open(target).read()
if snippet not in content:
    # loader.js 마커가 있으면 그 뒤에, 없으면 </body> 직전에 삽입
    loader_marker = '<script src="/static/loader.js" defer></script>'
    if loader_marker in content:
        content = content.replace(loader_marker, loader_marker + '\n\t\t' + snippet)
    else:
        content = content.replace('</body>', '\t\t' + snippet + '\n</body>')
    open(target, 'w').write(content)
