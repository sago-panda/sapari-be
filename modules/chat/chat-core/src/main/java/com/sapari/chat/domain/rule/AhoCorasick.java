package com.sapari.chat.domain.rule;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;

/**
 * 다중 패턴 동시 매칭용 Aho-Corasick 오토마톤(불변). 사전으로 한 번 빌드하고 검색만 한다.
 *
 * <p>실패 링크(failure link)로 한 번의 좌→우 스캔에 겹치는 패턴까지 모두 찾는다. 욕설 필터의
 * 1-패스 span 탐지와 2A 부분 매칭에 쓰인다. (입력 ≤200자라 점근 이득은 작지만 다중 패턴 정석 구조를 쓴다.)
 */
final class AhoCorasick {

    private static final class Node {
        final Map<Character, Node> children = new HashMap<>();
        Node fail;
        // 이 노드에서 끝나는 패턴 길이들 — 실패 링크 체인의 접미 패턴까지 병합해 둔다.
        final List<Integer> outputs = new ArrayList<>();
    }

    private final Node root = new Node();

    AhoCorasick(Collection<String> patterns) {
        for (String p : patterns) {
            if (p == null || p.isEmpty()) {
                continue;
            }
            Node node = root;
            for (int i = 0; i < p.length(); i++) {
                node = node.children.computeIfAbsent(p.charAt(i), k -> new Node());
            }
            node.outputs.add(p.length());
        }
        buildFailureLinks();
    }

    // BFS로 각 노드의 실패 링크 + 접미 패턴(dictionary suffix)을 구성한다.
    private void buildFailureLinks() {
        Queue<Node> queue = new ArrayDeque<>();
        for (Node child : root.children.values()) {
            child.fail = root;
            queue.add(child);
        }
        while (!queue.isEmpty()) {
            Node node = queue.poll();
            for (Map.Entry<Character, Node> entry : node.children.entrySet()) {
                char c = entry.getKey();
                Node child = entry.getValue();
                Node f = node.fail;
                while (f != root && !f.children.containsKey(c)) {
                    f = f.fail;
                }
                Node candidate = f.children.get(c);
                child.fail = (candidate != null && candidate != child) ? candidate : root;
                child.outputs.addAll(child.fail.outputs);
                queue.add(child);
            }
        }
    }

    /** text에 출현한 모든 패턴을 {@code [start, endExclusive)}로 반환(겹침 포함). */
    List<int[]> findAll(String text) {
        List<int[]> matches = new ArrayList<>();
        Node node = root;
        for (int i = 0; i < text.length(); i++) {
            node = advance(node, text.charAt(i));
            for (int len : node.outputs) {
                matches.add(new int[]{i - len + 1, i + 1});
            }
        }
        return matches;
    }

    /** text가 패턴을 하나라도 포함하면 true. */
    boolean containsAny(String text) {
        Node node = root;
        for (int i = 0; i < text.length(); i++) {
            node = advance(node, text.charAt(i));
            if (!node.outputs.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private Node advance(Node from, char c) {
        Node node = from;
        while (node != root && !node.children.containsKey(c)) {
            node = node.fail;
        }
        Node next = node.children.get(c);
        return next != null ? next : root;
    }
}
