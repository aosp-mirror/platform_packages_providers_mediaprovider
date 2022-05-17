#include <gtest/gtest.h>

#include "node-inl.h"

#include <algorithm>
#include <limits>
#include <memory>
#include <mutex>

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

    unique_node_ptr CreateNode(node* parent, const std::string& path, const int transforms = 0) {
        return unique_node_ptr(
                node::Create(parent, path, "", true, transforms, 0, &lock_, 0, &tracker_),
                &NodeTest::destroy);
    }

    static class node* ForChild(class node* node, const std::string& name,
                                const std::function<bool(class node*)>& callback) {
        return node->ForChild(name, callback);
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
    node* node = node::Create(nullptr, "/path", "", true, 0, 0, &lock_, 0, &tracker_);
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

TEST_F(NodeTest, TestRenameName) {
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

TEST_F(NodeTest, TestRenameParent) {
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

TEST_F(NodeTest, TestRenameNameAndParent) {
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

TEST_F(NodeTest, TestRenameNameForChild) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");

    unique_node_ptr child0 = CreateNode(parent.get(), "subdir", 0 /* transforms */);
    unique_node_ptr child1 = CreateNode(parent.get(), "subdir", 1 /* transforms */);
    ASSERT_EQ(3, GetRefCount(parent.get()));
    ASSERT_EQ(child0.get(),
              parent->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(child1.get(),
              parent->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));

    parent->RenameChild("subdir", "subdir_new", parent.get());

    ASSERT_EQ(3, GetRefCount(parent.get()));
    ASSERT_EQ(nullptr,
              parent->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(nullptr,
              parent->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));
    ASSERT_EQ(child0.get(),
              parent->LookupChildByName("subdir_new", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(child1.get(),
              parent->LookupChildByName("subdir_new", false /* acquire */, 1 /* transforms */));

    ASSERT_EQ("/path/subdir_new", child0->BuildPath());
    ASSERT_EQ("/path/subdir_new", child1->BuildPath());
    ASSERT_EQ(1, GetRefCount(child0.get()));
    ASSERT_EQ(1, GetRefCount(child1.get()));
}

TEST_F(NodeTest, TestRenameParentForChild) {
    unique_node_ptr parent1 = CreateNode(nullptr, "/path1");
    unique_node_ptr parent2 = CreateNode(nullptr, "/path2");

    unique_node_ptr child0 = CreateNode(parent1.get(), "subdir", 0 /* transforms */);
    unique_node_ptr child1 = CreateNode(parent1.get(), "subdir", 1 /* transforms */);
    ASSERT_EQ(3, GetRefCount(parent1.get()));
    ASSERT_EQ(child0.get(),
              parent1->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(child1.get(),
              parent1->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));

    parent1->RenameChild("subdir", "subdir", parent2.get());
    ASSERT_EQ(1, GetRefCount(parent1.get()));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));

    ASSERT_EQ(3, GetRefCount(parent2.get()));
    ASSERT_EQ(child0.get(),
              parent2->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(child1.get(),
              parent2->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));

    ASSERT_EQ("/path2/subdir", child0->BuildPath());
    ASSERT_EQ("/path2/subdir", child1->BuildPath());
    ASSERT_EQ(1, GetRefCount(child0.get()));
    ASSERT_EQ(1, GetRefCount(child1.get()));
}

TEST_F(NodeTest, TestRenameNameAndParentForChild) {
    unique_node_ptr parent1 = CreateNode(nullptr, "/path1");
    unique_node_ptr parent2 = CreateNode(nullptr, "/path2");

    unique_node_ptr child0 = CreateNode(parent1.get(), "subdir", 0 /* transforms */);
    unique_node_ptr child1 = CreateNode(parent1.get(), "subdir", 1 /* transforms */);
    ASSERT_EQ(3, GetRefCount(parent1.get()));
    ASSERT_EQ(child0.get(),
              parent1->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(child1.get(),
              parent1->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));

    parent1->RenameChild("subdir", "subdir_new", parent2.get());
    ASSERT_EQ(1, GetRefCount(parent1.get()));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir_new", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir_new", false /* acquire */, 1 /* transforms */));

    ASSERT_EQ(3, GetRefCount(parent2.get()));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir_new", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(nullptr,
              parent1->LookupChildByName("subdir_new", false /* acquire */, 1 /* transforms */));

    ASSERT_EQ("/path2/subdir_new", child0->BuildPath());
    ASSERT_EQ("/path2/subdir_new", child1->BuildPath());
    ASSERT_EQ(1, GetRefCount(child0.get()));
    ASSERT_EQ(1, GetRefCount(child1.get()));
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

TEST_F(NodeTest, TestSetDeletedForChild) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr child0 = CreateNode(parent.get(), "subdir", 0 /* transforms */);
    unique_node_ptr child1 = CreateNode(parent.get(), "subdir", 1 /* transforms */);

    ASSERT_EQ(child0.get(),
              parent->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(child1.get(),
              parent->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));
    parent->SetDeletedForChild("subdir");
    ASSERT_EQ(nullptr,
              parent->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(nullptr,
              parent->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));
}

TEST_F(NodeTest, DeleteTree) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");

    // This is the tree that we intend to delete.
    node* child = node::Create(parent.get(), "subdir", "", true, 0, 0, &lock_, 0, &tracker_);
    node::Create(child, "s1", "", true, 0, 0, &lock_, 0, &tracker_);
    node* subchild2 = node::Create(child, "s2", "", true, 0, 0, &lock_, 0, &tracker_);
    node::Create(subchild2, "sc2", "", true, 0, 0, &lock_, 0, &tracker_);

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

TEST_F(NodeTest, LookupChildByName_transforms) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr child0 = CreateNode(parent.get(), "subdir", 0 /* transforms */);
    unique_node_ptr child1 = CreateNode(parent.get(), "subdir", 1 /* transforms */);

    ASSERT_EQ(child0.get(), parent->LookupChildByName("subdir", false /* acquire */));
    ASSERT_EQ(child0.get(),
              parent->LookupChildByName("subdir", false /* acquire */, 0 /* transforms */));
    ASSERT_EQ(child1.get(),
              parent->LookupChildByName("subdir", false /* acquire */, 1 /* transforms */));
    ASSERT_EQ(nullptr,
              parent->LookupChildByName("subdir", false /* acquire */, 2 /* transforms */));
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

    handle* h = new handle(-1, new mediaprovider::fuse::RedactionInfo, true /* cached */,
                           false /* passthrough */, 0 /* uid */, 0 /* transforms_uid */);
    node->AddHandle(h);
    ASSERT_TRUE(node->HasCachedHandle());

    node->DestroyHandle(h);
    ASSERT_FALSE(node->HasCachedHandle());

    // Should all crash the process as the handle is no longer associated with
    // the node in question.
    EXPECT_DEATH(node->DestroyHandle(h), "");
    EXPECT_DEATH(node->DestroyHandle(nullptr), "");
    std::unique_ptr<handle> h2(new handle(-1, new mediaprovider::fuse::RedactionInfo,
                                          true /* cached */, false /* passthrough */, 0 /* uid */,
                                          0 /* transforms_uid */));
    EXPECT_DEATH(node->DestroyHandle(h2.get()), "");
}

TEST_F(NodeTest, CheckHandleForUid_foundSingle_shouldRedact) {
    unique_node_ptr node = CreateNode(nullptr, "/path");

    off64_t ranges[2] = {0, 1};
    mediaprovider::fuse::RedactionInfo* infoWithLocation =
            new mediaprovider::fuse::RedactionInfo(1, ranges);

    handle* h = new handle(-1, infoWithLocation, true /* cached */, false /* passthrough */,
                           1 /* uid */, 0 /* transforms_uid */);

    node->AddHandle(h);
    std::unique_ptr<mediaprovider::fuse::FdAccessResult> res(node->CheckHandleForUid(1));
    ASSERT_TRUE(res->should_redact);
    ASSERT_EQ(res->file_path, "/path");
}

TEST_F(NodeTest, CheckHandleForUid_foundSingle_shouldNotRedact) {
    unique_node_ptr node = CreateNode(nullptr, "/path");

    mediaprovider::fuse::RedactionInfo* infoWithoutLocation = new mediaprovider::fuse::RedactionInfo;

    handle* h = new handle(-1, infoWithoutLocation, true /* cached */, false /* passthrough */,
                           1 /* uid */, 0 /* transforms_uid */);

    node->AddHandle(h);
    std::unique_ptr<mediaprovider::fuse::FdAccessResult> res(node->CheckHandleForUid(1));
    ASSERT_FALSE(res->should_redact);
    ASSERT_EQ(res->file_path, "/path");
}

TEST_F(NodeTest, CheckHandleForUid_foundMultiple_shouldNotRedact) {
    unique_node_ptr node = CreateNode(nullptr, "/path");

    off64_t ranges[2] = {0, 1};
    mediaprovider::fuse::RedactionInfo* infoWithLocation =
            new mediaprovider::fuse::RedactionInfo(1, ranges);
    mediaprovider::fuse::RedactionInfo* infoWithoutLocation = new mediaprovider::fuse::RedactionInfo;

    handle* h1 = new handle(-1, infoWithLocation, true /* cached */, false /* passthrough */,
                            1 /* uid */, 0 /* transforms_uid */);
    handle* h2 = new handle(-1, infoWithoutLocation, true /* cached */, false /* passthrough */,
                            1 /* uid */, 0 /* transforms_uid */);

    node->AddHandle(h1);
    node->AddHandle(h2);
    std::unique_ptr<mediaprovider::fuse::FdAccessResult> res(node->CheckHandleForUid(1));
    ASSERT_FALSE(res->should_redact);
    ASSERT_EQ(res->file_path, "/path");
}

TEST_F(NodeTest, CheckHandleForUid_notFound_differentUid) {
    unique_node_ptr node = CreateNode(nullptr, "/path");

    off64_t ranges[2] = {0, 1};
    mediaprovider::fuse::RedactionInfo* infoWithLocation =
            new mediaprovider::fuse::RedactionInfo(1, ranges);

    handle* h = new handle(-1, infoWithLocation, true /* cached */, false /* passthrough */,
                           2 /* uid */, 0 /* transforms_uid */);

    node->AddHandle(h);
    std::unique_ptr<mediaprovider::fuse::FdAccessResult> res(node->CheckHandleForUid(1));
    ASSERT_FALSE(res->should_redact);
    ASSERT_EQ(res->file_path, "");
}

TEST_F(NodeTest, CheckHandleForUid_notFound_noHandle) {
    unique_node_ptr node = CreateNode(nullptr, "/path");

    std::unique_ptr<mediaprovider::fuse::FdAccessResult> res(node->CheckHandleForUid(1));
    ASSERT_FALSE(res->should_redact);
    ASSERT_EQ(res->file_path, "");
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

TEST_F(NodeTest, ForChild) {
    unique_node_ptr parent = CreateNode(nullptr, "/path");
    unique_node_ptr foo1 = CreateNode(parent.get(), "FoO");
    unique_node_ptr foo2 = CreateNode(parent.get(), "fOo");
    unique_node_ptr foo3 = CreateNode(parent.get(), "foo");
    foo3->SetDeleted();

    std::vector<node*> match_all;
    auto test_fn_match_all = [&](node* child) {
        match_all.push_back(child);
        return false;
    };

    std::vector<node*> match_first;
    auto test_fn_match_first = [&](node* child) {
        match_first.push_back(child);
        return true;
    };

    std::vector<node*> match_none;
    auto test_fn_match_none = [&](node* child) {
        match_none.push_back(child);
        return false;
    };

    node* node_all = ForChild(parent.get(), "foo", test_fn_match_all);
    ASSERT_EQ(nullptr, node_all);
    ASSERT_EQ(2, match_all.size());
    ASSERT_EQ(std::min(foo1.get(), foo2.get()), match_all[0]);
    ASSERT_EQ(std::max(foo1.get(), foo2.get()), match_all[1]);

    node* node_first = ForChild(parent.get(), "foo", test_fn_match_first);
    ASSERT_EQ(std::min(foo1.get(), foo2.get()), node_first);
    ASSERT_EQ(1, match_first.size());
    ASSERT_EQ(std::min(foo1.get(), foo2.get()), match_first[0]);

    node* node_none = ForChild(parent.get(), "bar", test_fn_match_none);
    ASSERT_EQ(nullptr, node_none);
    ASSERT_TRUE(match_none.empty());
}
