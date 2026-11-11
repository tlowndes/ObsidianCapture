import json
lines=open('/root/.claude/projects/-home-user/464d233f-edb3-5f7a-b072-feb416bc488a.jsonl').read().splitlines()
for l in reversed(lines):
    try: o=json.loads(l)
    except: continue
    m=o.get('message',{})
    if m.get('role')!='assistant': continue
    for c in m.get('content',[]) if isinstance(m.get('content'),list) else []:
        if c.get('type')=='tool_use' and 'Add drag-to-slot to phone Upcoming list' in json.dumps(c.get('input',{})):
            cmd=c['input']['command']
            body=cmd.split("<<'EOF'\n",1)[1].rsplit("\nEOF",1)[0]
            body=body.replace("rep('''            Text(TaskViews.plainText(t), fontSize = 14.sp''','''            Text(DayPlan.bareText(TaskViews.plainText(t)), fontSize = 14.sp''')",
              "rep('''            Text(TaskViews.plainText(t), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (ticked)''','''            Text(DayPlan.bareText(TaskViews.plainText(t)), fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = if (ticked)''')")
            open('drag.py','w').write(body); print('saved', len(body)); raise SystemExit