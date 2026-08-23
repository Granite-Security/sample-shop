package org.granitesecurity.greetings.research.binary;

public class BinaryTree {
    Node root;

    public BinaryTree() {
        this.root = null;
    }

    public BinaryTree(Node root) {
        this.root = root;
    }

    public void add(int value) {
        Node n = addRecursive(this.root, value);
    }

    private Node addRecursive(Node current, int value) {
        if (current == null) {
            current = new Node(value);
            return current;
        }
        if (value >= current.getData()) {
            return addRecursive(current.getRight(), value);
        }
        return addRecursive(root.getLeft(), value);
    }


}
