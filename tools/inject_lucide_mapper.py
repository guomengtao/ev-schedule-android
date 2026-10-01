#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
inject_lucide_mapper.py
Safely unify ALL text/emoji icons in app-auth/admin_Dx23.html to Lucide by
injecting a runtime DOM mapper. The mapper converts any emoji/unicode cyph in
rendered text into the equivalent lucide SVG (via data-lucide + 
lucide.createIcons), covering both static HTML and dynamic content.

Why runtime instead of source-string rewriting:
  - The file ships a single ~361KB inline <script> (regex literals, nested
    strings, template literals), so hand-mangling string literals is unsafe for
    this live admin page. This approach has zero syntax risk.

Safety:
  - Creates a timestamped backup before writing.
  - Re-injection guarded by a data-lucide-mapper marker.

Run: python3 tools/inject_lucide_mapper.py
"""
import os
import json
import re
import shutil
import sys
import time

TARGET = "/Users/Banner/Documents/guomengtao/app-auth/admin_Dx23.html"
MARKER = 'data-lucide-mapper'

# emoji / unicode glyph -> lucide icon name  (kept in sync with analysis doc)
GLYPHS = {
    "❌": "x-circle", "✅": "check-circle", "🔄": "refresh-cw",
    "⚠": "alert-triangle", "✓": "check", "→": "arrow-right",
    "▶": "play", "📨": "send", "👤": "user", "💾": "save",
    "🕐": "clock", "▼": "chevron-down", "📊": "bar-chart-3", "⚙": "settings",
    "📤": "upload", "📧": "mail", "👁": "eye", "📱": "smartphone",
    "📬": "mailbox", "📦": "package", "➕": "plus", "🗑": "trash-2",
    "⚡": "zap", "🔗": "link", "🚫": "ban", "●": "circle",
    "🌐": "globe", "🔍": "search", "💡": "lightbulb", "🗄": "archive",
    "🔴": "circle", "🔵": "circle", "🟡": "circle", "🟢": "circle",
    "🟠": "circle", "🟣": "circle", "⚪": "circle", "⚫": "circle",
    "✕": "x", "✗": "x", "📉": "trending-down", "←": "arrow-left",
    "○": "circle", "✎": "edit", "📜": "scroll-text", "🚀": "rocket",
    "💬": "message-circle", "↗": "arrow-up-right", "🔊": "volume-2",
    "🎨": "palette", "🛡": "shield", "📝": "file-text", "📸": "camera",
    "🎫": "ticket", "💖": "heart", "▲": "chevron-up", "↩": "corner-up-left",
    "📖": "book-open", "🙈": "eye-off", "🏆": "trophy", "🎯": "target",
    "🧭": "compass", "💰": "coins", "🎉": "party-popper", "☰": "menu",
    "📐": "ruler", "📅": "calendar", "🌲": "tree-pine", "🌳": "tree-pine",
    "🌙": "moon", "📥": "download", "📮": "mailbox", "♾": "infinity",
    "⬇": "arrow-down", "🔁": "repeat", "🔐": "lock", "🔔": "bell",
    "🧪": "flask-conical", "🤖": "bot", "📡": "radio-tower", "📭": "inbox",
    "✏": "pencil", "↓": "arrow-down", "↑": "arrow-up", "🧬": "dna",
    "🌍": "earth", "🗂": "folder-open", "📲": "smartphone",
    "↔": "move-horizontal", "🧩": "puzzle", "🏷": "tag", "🎟": "ticket",
    "🔎": "search", "❓": "help-circle", "🖥": "monitor", "📁": "folder",
    "🔌": "plug", "🔇": "volume-x", "⬛": "square", "⬜": "square",
    # gap fill from missed-icon analysis
    "⏭": "skip-forward", "⏰": "alarm-clock", "⏱": "timer", "⏳": "hourglass",
    "⏸": "pause", "👆": "pointer", "📋": "clipboard-list",
}

CSS_RULE = ("<style>\n    .lucide-inline{width:1em;height:1em;display:inline-block;"
            "vertical-align:-0.15em;margin-right:4px;flex:none}\n</style>")


MAPPER_JS = r"""
<script data-lucide-mapper="1">
(function () {
  if (typeof lucide === 'undefined' || !lucide.createIcons) { return; }
  var MAP = {
__MAP_BODY__
  };
  var keys = Object.keys(MAP);
  function esc(s) { return s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'); }
  var RE = new RegExp('(' + keys.map(esc).join('|') + ')', 'g');

  function swap(textNode) {
    var data = textNode.data;
    RE.lastIndex = 0;
    if (!RE.test(data)) { RE.lastIndex = 0; return; }
    RE.lastIndex = 0;
    var frag = document.createDocumentFragment();
    var last = 0, m;
    while ((m = RE.exec(data)) !== null) {
      if (m.index > last) { frag.appendChild(document.createTextNode(data.slice(last, m.index))); }
      var i = document.createElement('i');
      i.setAttribute('data-lucide', MAP[m[0]]);
      i.setAttribute('class', 'lucide-inline');
      frag.appendChild(i);
      last = m.index + m[0].length;
      if (data.charCodeAt(last) === 0xFE0F) { last += 1; }
    }
    if (last < data.length) { frag.appendChild(document.createTextNode(data.slice(last))); }
    textNode.parentNode.replaceChild(frag, textNode);
  }

  function scan() {
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
    var nodes = [];
    while (walker.nextNode()) { nodes.push(walker.currentNode); }
    var changed = false;
    for (var i = 0; i < nodes.length; i += 1) {
      RE.lastIndex = 0;
      if (nodes[i].data && RE.test(nodes[i].data)) { swap(nodes[i]); changed = true; }
    }
    if (changed) { lucide.createIcons(); }
  }

  function start() {
    if (typeof lucide === 'undefined' || !lucide.createIcons) { return; }
    scan();
    if (typeof MutationObserver !== 'undefined' && document.body) {
      new MutationObserver(function () { scan(); }).observe(document.body, { childList: true, subtree: true });
    }
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', start);
  } else {
    start();
  }
})();
</script>
"""


def main():
    if not os.path.exists(TARGET):
        sys.exit('[ERR] target not found: ' + TARGET)
    content = open(TARGET, encoding='utf-8').read()

    # json.dumps emits JS-valid escapes (\uXXXX / surrogate pairs) for emoji
    body = ",\n".join('    %s: %s' % (json.dumps(k), json.dumps(v))
                     for k, v in sorted(GLYPHS.items()))
    js = MAPPER_JS.replace('__MAP_BODY__', body)

    vector = CSS_RULE + "\n" + js

    # remove any previously injected block so re-runs apply the latest map.
    # NOTE: anchors must be precise to ONLY match our injected content (never the
    # <head> styles / body). Do not use [\s\S]*? from an earlier <style>.
    content = re.sub(r'<\s*style\b[^>]*>\s*\.lucide-inline\s*\{[^}]*\}\s*<\s*/\s*style\s*>',
                     '', content)
    content = re.sub(
        r'<\s*script\s+data-lucide-mapper="1"[^>]*>[\s\S]*?<\s*/\s*script\s*>', '', content)

    bottom = '</body>'
    if bottom in content:
        content = content.replace(bottom, vector + "\n" + bottom, 1)
    else:
        content = content.rstrip() + "\n" + vector + "\n</html>\n"

    bak = TARGET + '.bak-' + time.strftime('%Y%m%d-%H%M%S')
    shutil.copy2(TARGET, bak)
    with open(TARGET, 'w', encoding='utf-8') as f:
        f.write(content)

    print('[ok] Mapper injected into ' + TARGET)
    print('[ok] Backup: ' + bak)


if __name__ == '__main__':
    main()