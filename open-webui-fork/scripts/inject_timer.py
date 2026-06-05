target = '/app/build/index.html'
snippet = '<script src="/static/elapsed-timer.js" defer></script>'
marker = '<script src="/static/loader.js" defer></script>'

content = open(target).read()
if snippet not in content:
    content = content.replace(marker, marker + '\n\t\t' + snippet)
    open(target, 'w').write(content)
