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
    """'key: [a, b]' 에서 목록 원소를 뽑는다."""
    return [x.strip().strip('"') for x in line[line.index("[") + 1:line.rindex("]")].split(",") if x.strip()]


def glob_to_regex(glob):
    """'**/' 는 0개 이상의 디렉터리. fnmatch 는 '**' 를 모르므로 직접 옮긴다."""
    import re
    return (re.escape(glob).replace(r"\*\*/", "(?:.*/)?").replace(r"\*\*", ".*")
            .replace(r"\*", "[^/]*").replace(r"\?", "[^/]"))
