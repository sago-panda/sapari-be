"""anchors.yml 파싱 공용 함수.

세 스크립트(resolve-anchors / check-anchor-globs / check-item-ids)가 같은 파일을 읽는데,
파싱을 각자 구현하면 한 곳만 고쳐졌을 때 검사하는 쪽이 fail-open 이 된다. 실제로
다중 줄 목록과 주석 속 대괄호에서 그 일이 두 번 났다.
"""


def logical_lines(path):
    """주석을 걷어내고, 대괄호가 닫힐 때까지 줄을 이어 한 줄처럼 돌려준다.

    주석 속 '[' 하나가 카운팅을 깨뜨리면 뒤 규칙들이 한 줄로 뭉쳐 조용히 사라진다.
    글롭의 문자 클래스([abc])도 같은 문제를 만들므로 따옴표 밖만 센다.
    """
    def strip_comment(line):
        out, quoted = [], False
        for ch in line:
            if ch == '"':
                quoted = not quoted
            if ch == "#" and not quoted:
                break
            out.append(ch)
        return "".join(out).rstrip()

    def unquoted_depth(text):
        depth, quoted = 0, False
        for ch in text:
            if ch == '"':
                quoted = not quoted
            elif not quoted and ch == "[":
                depth += 1
            elif not quoted and ch == "]":
                depth -= 1
        return depth

    buf = ""
    for raw in open(path, encoding="utf-8"):
        line = strip_comment(raw.rstrip("\n"))
        if not line.strip() and not buf:
            continue
        buf = (buf + " " + line.strip()) if buf else line
        if unquoted_depth(buf) > 0:
            continue
        yield buf
        buf = ""
    if buf:
        # 대괄호가 안 닫힌 채 파일이 끝났다. 그대로 넘기면 나머지 전부가 한 논리 줄로
        # 합쳐져 규칙 여러 개가 조용히 사라진다.
        raise ValueError(f"{path}: 닫히지 않은 대괄호 — 마지막 조각: {buf[:80]!r}")


def items(line):
    """'key: [a, b]' 에서 목록 원소를 뽑는다.

    따옴표가 있으면 따옴표 단위로 뽑는다. 쉼표로만 쪼개면 정규식 속 쉼표({1,3})에서
    패턴이 두 조각이 되어, harness-check(따옴표 기준)는 통과하고 리뷰 시점에는 조용히
    안 켜진다 — 두 파서가 같은 원소를 봐야 한다.
    """
    import re
    body = line[line.index("[") + 1:line.rindex("]")]
    if '"' in body:
        return re.findall(r'"([^"]+)"', body)
    return [x.strip() for x in body.split(",") if x.strip()]


def glob_to_regex(glob):
    """'**/' 는 0개 이상의 디렉터리. fnmatch 는 '**' 를 모르므로 직접 옮긴다."""
    import re
    return (re.escape(glob).replace(r"\*\*/", "(?:.*/)?").replace(r"\*\*", ".*")
            .replace(r"\*", "[^/]*").replace(r"\?", "[^/]"))


REVIEWERS_FILE = ".claude/reviewers.yml"
COMMON_FILE = ".claude/review/common.md"


def reviewers(path=REVIEWERS_FILE):
    """reviewers.yml 을 읽어 리뷰어 목록을 돌려준다.

    각 원소: name, prefixes(도메인 접두사 포함), methodology, domains, files(방법론 + 도메인 체크리스트).
    도메인 리뷰어는 접두사가 도메인명 대문자, 체크리스트가 .claude/review/domains/<domain>.md 다.
    """
    out, cur = [], None
    for line in logical_lines(path):
        s = line.strip()
        if s.startswith("- name:"):
            cur = {"name": s.split(":", 1)[1].strip(), "prefixes": [], "domains": [], "methodology": None}
            out.append(cur)
        elif cur is None:
            continue
        elif s.startswith("prefixes:"):
            cur["prefixes"] = items(s)
        elif s.startswith("domains:"):
            cur["domains"] = items(s)
        elif s.startswith("methodology:"):
            cur["methodology"] = s.split(":", 1)[1].strip()
    for r in out:
        if not r["methodology"]:
            raise ValueError(f"{path}: {r['name']} 에 methodology 가 없다")
        r["prefixes"] = r["prefixes"] + [d.upper() for d in r["domains"]]
        if not r["prefixes"]:
            raise ValueError(f"{path}: {r['name']} 에 prefixes 도 domains 도 없다")
        r["files"] = [r["methodology"]] + [f".claude/review/domains/{d}.md" for d in r["domains"]]
    seen = {}
    for r in out:
        for p in r["prefixes"]:
            if p in seen:
                raise ValueError(f"{path}: 접두사 {p} 를 {seen[p]} 와 {r['name']} 이 함께 소유한다")
            seen[p] = r["name"]
    return out


def prefix_owner(revs):
    """접두사 -> 리뷰어 원소."""
    return {p: r for r in revs for p in r["prefixes"]}
