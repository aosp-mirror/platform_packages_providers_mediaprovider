#include <gtest/gtest.h>

#include "node-inl.h"

#include <algorithm>
#include <limits>
#include <memory>
#include <mutex>

using mediaprovider::fuse::dirhandle;
using mediaprovider::fuse::handle;
using mediaprovider::fuse::node;
using mediaprovider::fuse::NodeTracker;

// Listed as a friend class to struct node so it can observe implementation
// details if required. The only implementation detail that is worth writing
// tests around at the moment is the reference count.
class NodeTest : public ::testing::Test {
  public:
    NodeTest() : tracker_(NodeTracker(&lock_)) {}

    uint32_t GetRefCount(node* node) { return node->refcount_; }

    std::recursive_mutex lock_;
    NodeTracker tracker_;

    // Forward destruction here, as NodeTest is a friend class.
    static void destroy(node* node) { delete node; }

    static void acquire(node* node) { node->Acquire(); }

    typedef std::unique_ptr<node, decltype(&NodeTest::destroy)> unique_node_ptr;

    unique_node_ptr CreateNode(node* parent, const std::string& path) {
        return unique_node_ptr(node::Create(parent, path, &lock_, &tracker_), &NodeTest::destroy);
    }

    // Expose NodeCompare for testing.
    node::NodeCompare cmp;
};

TEST_F(NodeTest, TestCreate) {
    unique_node_ptr node = CreateNode(nullptr, "/path");

    ASSERT_EQ("/path", node->GetName());
    ASSERT_EQ(1, GetRefCount(node.get()));
    ASSERT_FALSE(node->HasCachedHandle());
}

TEST_F(NodeTest, TestCreate_withParent) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    ASSERT_EQ(1, GetRefCount(parent.get()));

    // Adding a child to a parent node increments its refcount.
    unique_node_ptr child = CreateNode(parent.get(), "subdir");
    ASSERT_EQ(2, GetRefCount(parent.get()));

    // Make sure the node has been added to the parents list of children.
    ASSERT_EQ(child.get(), parent->LookupChildByName("subdir", false /* acquire */));
    ASSERT_EQ(1, GetRefCount(child.get()));
}

TEST_F(NodeTest, TestRelease) {
    node* node = node::Create(nullptr, "/path", &lock_, &tracker_);
    acquire(node);
    acquire(node);
    ASSERT_EQ(3, GetRefCount(node));

    ASSERT_FALSE(node->Release(1));
    ASSERT_EQ(2, GetRefCount(node));

    // A Release that makes refcount go negative should be a no-op.
    ASSERT_FALSE(node->Release(10000));
    ASSERT_EQ(2, GetRefCount(node));

    // Finally, let the refcount go to zero.
    ASSERT_TRUE(node->Release(2));
}

TEST_F(NodeTest, TestRenameWithName) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");

    unique_node_ptr child = CreateNode(parent.get(), "subdir");
    ASSERT_EQ(2, GetRefCount(parent.get()));
    ASSERT_EQ(child.get(), parent->LookupChildByName("subdir", false /* acquire */));

    child->Rename("subdir_new", parent.get());

    ASSERT_EQ(2, GetRefCount(parent.get()));
    ASSERT_EQ(nullptr, parent->LookupChildByName("subdir", false /* acquire */));
    ASSERT_EQ(child.get(), parent->LookupChildByName("subdir_new", false /* acquire */));

    ASSERT_EQ("/path/subdir_new", child->BuildPath());
    ASSERT_EQ(1, GetRefCount(child.get()));
}

TEST_F(NodeTest, TestRenameWithParent) {
    unique_node_ptr parent1 = CreateNode(nullptr, "/path1");
    unique_node_ptr parent2 = CreateNode(nullptr, "/path2");

    unique_node_ptr child = CreateNode(parent1.get(), "subdir");
    ASSERT_EQ(2, GetRefCount(parent1.get()));
    ASSERT_EQ(child.get(), parent1->LookupChildByName("subdir", false /* acquire */));

    child->Rename("subdir", parent2.get());
    ASSERT_EQ(1, GetRefCount(parent1.get()));
    ASSERT_EQ(nullptr, parent1->LookupChildByName("subdir", false /* acquire */));

    ASSERT_EQ(2, GetRefCount(parent2.get()));
    ASSERT_EQ(child.get(), parent2->LookupChildByName("subdir", false /* acquire */));

    ASSERT_EQ("/path2/subdir", child->BuildPath());
    ASSERT_EQ(1, GetRefCount(child.get()));
}

TEST_F(NodeTest, TestRenameWithNameAndParent) {
    unique_node_ptr parent1 = CreateNode(nullptr, "/path1");
    unique_node_ptr parent2 = CreateNode(nullptr, "/path2");

    unique_node_ptr child = CreateNode(parent1.get(), "subdir");
    ASSERT_EQ(2, GetRefCount(parent1.get()));
    ASSERT_EQ(child.get(), parent1->LookupChildByName("subdir", false /* acquire */));

    child->Rename("subdir_new", parent2.get());
    ASSERT_EQ(1, GetRefCount(parent1.get()));
    ASSERT_EQ(nullptr, parent1->LookupChildByName("subdir", false /* acquire */));
    ASSERT_EQ(nullptr, parent1->LookupChildByName("subdir_new", false /* acquire */));

    ASSERT_EQ(2, GetRefCount(parent2.get()));
    ASSERT_EQ(child.get(), parent2->LookupChildByName("subdir_new", false /* acquire */));

    ASSERT_EQ("/path2/subdir_new", child->BuildPath());
    ASSERT_EQ(1, GetRefCount(child.get()));
}

TEST_F(NodeTest, TestBuildPath) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    ASSERT_EQ("/path", parent->BuildPath());

    unique_node_ptr child = CreateNode(parent.get(), "subdir");
    ASSERT_EQ("/path/subdir", child->BuildPath());

    unique_node_ptr child2 = CreateNode(parent.get(), "subdir2");
    ASSERT_EQ("/path/subdir2", child2->BuildPath());

    unique_node_ptr subchild = CreateNode(child2.get(), "subsubdir");
    ASSERT_EQ("/path/subdir2/subsubdir", subchild->BuildPath());
}

TEST_F(NodeTest, TestSetDeleted) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr child = CreateNode(parent.get(), "subdir");

    ASSERT_EQ(child.get(), parent->LookupChildByName("subdir", false /* acquire */));
    child->SetDeleted();
    ASSERT_EQ(nullptr, parent->LookupChildByName("subdir", false /* acquire */));
}

TEST_F(NodeTest, DeleteTree) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");

    // This is the tree that we intend to delete.
    node* child = node::Create(parent.get(), "subdir", &lock_, &tracker_);
    node::Create(child, "s1", &lock_, &tracker_);
    node* subchild2 = node::Create(child, "s2", &lock_, &tracker_);
    node::Create(subchild2, "sc2", &lock_, &tracker_);

    ASSERT_EQ(child, parent->LookupChildByName("subdir", false /* acquire */));
    node::DeleteTree(child);
    ASSERT_EQ(nullptr, parent->LookupChildByName("subdir", false /* acquire */));
}

TEST_F(NodeTest, LookupChildByName_empty) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr child = CreateNode(parent.get(), "subdir");

    ASSERT_EQ(child.get(), parent->LookupChildByName("subdir", false /* acquire */));
    ASSERT_EQ(nullptr, parent->LookupChildByName("", false /* acquire */));
}

TEST_F(NodeTest, LookupChildByName_refcounts) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr child = CreateNode(parent.get(), "subdir");

    ASSERT_EQ(child.get(), parent->LookupChildByName("subdir", false /* acquire */));
    ASSERT_EQ(1, GetRefCount(child.get()));

    ASSERT_EQ(child.get(), parent->LookupChildByName("subdir", true /* acquire */));
    ASSERT_EQ(2, GetRefCount(child.get()));
}

TEST_F(NodeTest, LookupAbsolutePath) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr child = CreateNode(parent.get(), "subdir");
    unique_node_ptr child2 = CreateNode(parent.get(), "subdir2");
    unique_node_ptr subchild = CreateNode(child2.get(), "subsubdir");

    ASSERT_EQ(parent.get(), node::LookupAbsolutePath(parent.get(), "/path"));
    ASSERT_EQ(parent.get(), node::LookupAbsolutePath(parent.get(), "/path/"));
    ASSERT_EQ(nullptr, node::LookupAbsolutePath(parent.get(), "/path2"));

    ASSERT_EQ(child.get(), node::LookupAbsolutePath(parent.get(), "/path/subdir"));
    ASSERT_EQ(child.get(), node::LookupAbsolutePath(parent.get(), "/path/subdir/"));
    // TODO(narayan): Are the two cases below intentional behaviour ?
    ASSERT_EQ(child.get(), node::LookupAbsolutePath(parent.get(), "/path//subdir"));
    ASSERT_EQ(child.get(), node::LookupAbsolutePath(parent.get(), "/path///subdir"));

    ASSERT_EQ(child2.get(), node::LookupAbsolutePath(parent.get(), "/path/subdir2"));
    ASSERT_EQ(child2.get(), node::LookupAbsolutePath(parent.get(), "/path/subdir2/"));

    ASSERT_EQ(nullptr, node::LookupAbsolutePath(parent.get(), "/path/subdir3/"));

    ASSERT_EQ(subchild.get(), node::LookupAbsolutePath(parent.get(), "/path/subdir2/subsubdir"));
    ASSERT_EQ(nullptr, node::LookupAbsolutePath(parent.get(), "/path/subdir/subsubdir"));
}

TEST_F(NodeTest, AddDestroyHandle) {
    unique_node_ptr node = CreateNode(nullptr, "/path");

    handle* h = new handle(-1, new mediaprovider::fuse::RedactionInfo, true /* cached */);
    node->AddHandle(h);
    ASSERT_TRUE(node->HasCachedHandle());

    node->DestroyHandle(h);
    ASSERT_FALSE(node->HasCachedHandle());

    // Should all crash the process as the handle is no longer associated with
    // the node in question.
    EXPECT_DEATH(node->DestroyHandle(h), "");
    EXPECT_DEATH(node->DestroyHandle(nullptr), "");
    std::unique_ptr<handle> h2(
            new handle(-1, new mediaprovider::fuse::RedactionInfo, true /* cached */));
    EXPECT_DEATH(node->DestroyHandle(h2.get()), "");
}

TEST_F(NodeTest, CaseInsensitive) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr mixed_child = CreateNode(parent.get(), "cHiLd");

    node* upper_child = parent->LookupChildByName("CHILD", false /* acquire */);
    node* lower_child = parent->LookupChildByName("child", false /* acquire */);

    ASSERT_EQ(mixed_child.get(), lower_child);
    ASSERT_EQ(mixed_child.get(), upper_child);
}

TEST_F(NodeTest, RenameSameNameSameParent) {
    unique_node_ptr parent = CreateNode(nullptr, "/path1");
    unique_node_ptr child = CreateNode(parent.get(), "subdir");

    ASSERT_EQ(child.get(), parent->LookupChildByName("SuBdIr", false /* acquire */));
    ASSERT_EQ(2, GetRefCount(parent.get()));

    child->Rename("subdir", parent.get());

    ASSERT_EQ(child.get(), parent->LookupChildByName("SuBdIr", false /* acquire */));
    ASSERT_EQ(2, GetRefCount(parent.get()));
}

TEST_F(NodeTest, RenameRoot) {
    unique_node_ptr root = CreateNode(nullptr, "/root");
    ASSERT_EQ(1, GetRefCount(root.get()));

    root->Rename("/i-am-root!", nullptr);

    ASSERT_EQ("/i-am-root!", root->GetName());
    ASSERT_EQ(1, GetRefCount(root.get()));
}

TEST_F(NodeTest, NodeCompareDefinesLinearOrder) {
    unique_node_ptr node_a = CreateNode(nullptr, "a");
    unique_node_ptr node_b = CreateNode(nullptr, "B");
    unique_node_ptr node_c = CreateNode(nullptr, "c");

    ASSERT_FALSE(cmp.operator()(node_a.get(), node_a.get()));
    ASSERT_FALSE(cmp.operator()(node_b.get(), node_b.get()));
    ASSERT_FALSE(cmp.operator()(node_c.get(), node_c.get()));

    auto check_fn = [&](const node* lhs_node, const node* rhs_node) {
        ASSERT_TRUE(cmp.operator()(lhs_node, rhs_node));
        ASSERT_FALSE(cmp.operator()(rhs_node, lhs_node));
    };

    check_fn(node_a.get(), node_b.get());
    check_fn(node_b.get(), node_c.get());
    check_fn(node_a.get(), node_c.get());

    // ("A", 0) < node_a < ("a", max_uintptr_t) < node_b
    ASSERT_TRUE(cmp.operator()(std::make_pair("A", 0), node_a.get()));
    ASSERT_TRUE(cmp.operator()(node_a.get(),
                               std::make_pair("A", std::numeric_limits<uintptr_t>::max())));
    ASSERT_TRUE(cmp.operator()(std::make_pair("A", std::numeric_limits<uintptr_t>::max()),
                               node_b.get()));
}

TEST_F(NodeTest, LookupChildByName_ChildrenWithSameName) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr foo1 = CreateNode(parent.get(), "FoO");
    unique_node_ptr foo2 = CreateNode(parent.get(), "fOo");
    unique_node_ptr bar1 = CreateNode(parent.get(), "BAR");
    unique_node_ptr bar2 = CreateNode(parent.get(), "bar");
    unique_node_ptr baz1 = CreateNode(parent.get(), "baZ");
    unique_node_ptr baz2 = CreateNode(parent.get(), "Baz");

    auto test_fn = [&](const std::string& name, node* first, node* second) {
        auto node1 = parent->LookupChildByName(name, false /* acquire */);
        ASSERT_EQ(std::min(first, second), node1);
        node1->SetDeleted();

        auto node2 = parent->LookupChildByName(name, false /* acquire */);
        ASSERT_EQ(std::max(first, second), node2);
        node2->SetDeleted();

        ASSERT_EQ(nullptr, parent->LookupChildByName(name, false /* acquire */));
    };

    test_fn("foo", foo1.get(), foo2.get());
    test_fn("bAr", bar1.get(), bar2.get());
    test_fn("BaZ", baz1.get(), baz2.get());
}
