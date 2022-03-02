/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.providers.media.photopicker.ui;

import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.CompositePageTransformer;
import androidx.viewpager2.widget.ViewPager2;

import com.android.providers.media.R;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;

/**
 * The tab container fragment
 */
public class TabContainerFragment extends Fragment {
    private static final String TAG = "TabContainerFragment";
    private static final int PHOTOS_TAB_POSITION = 0;
    private static final int ALBUMS_TAB_POSITION = 1;

    private TabContainerAdapter mTabContainerAdapter;
    private TabLayoutMediator mTabLayoutMediator;
    private ViewPager2 mViewPager;

    @Override
    @NonNull
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        super.onCreateView(inflater, container, savedInstanceState);
        return inflater.inflate(R.layout.fragment_picker_tab_container, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mTabContainerAdapter = new TabContainerAdapter(/* fragment */ this);
        mViewPager = view.findViewById(R.id.picker_tab_viewpager);
        mViewPager.setAdapter(mTabContainerAdapter);

        // If the ViewPager2 has more than one page with BottomSheetBehavior, the scrolled view
        // (e.g. RecyclerView) on the second page can't be scrolled. The workaround is to update
        // nestedScrollingChildRef to the scrolled view on the current page. b/145334244
        Field fieldNestedScrollingChildRef = null;
        try {
            fieldNestedScrollingChildRef = BottomSheetBehavior.class.getDeclaredField(
                    "nestedScrollingChildRef");
            fieldNestedScrollingChildRef.setAccessible(true);
        } catch (NoSuchFieldException ex) {
            Log.d(TAG, "Can't get the field nestedScrollingChildRef from BottomSheetBehavior", ex);
        }

        final BottomSheetBehavior bottomSheetBehavior = BottomSheetBehavior.from(
                getActivity().findViewById(R.id.bottom_sheet));

        final CompositePageTransformer compositePageTransformer = new CompositePageTransformer();
        mViewPager.setPageTransformer(compositePageTransformer);
        compositePageTransformer.addTransformer(new AnimationPageTransformer());
        compositePageTransformer.addTransformer(
                new NestedScrollPageTransformer(bottomSheetBehavior, fieldNestedScrollingChildRef));

        // The BottomSheetBehavior looks for the first nested scrolling child to determine how to
        // handle nested scrolls, it finds the inner recyclerView on ViewPager2 in this case. So, we
        // need to work around it by setNestedScrollingEnabled false. b/145351873
        final View firstChild = mViewPager.getChildAt(0);
        if (firstChild instanceof RecyclerView) {
            mViewPager.getChildAt(0).setNestedScrollingEnabled(false);
        }

        final TabLayout tabLayout = getActivity().findViewById(R.id.tab_layout);
        mTabLayoutMediator = new TabLayoutMediator(tabLayout, mViewPager, (tab, pos) -> {
            if (pos == PHOTOS_TAB_POSITION) {
                tab.setText(R.string.picker_photos);
            } else if (pos == ALBUMS_TAB_POSITION) {
                tab.setText(R.string.picker_albums);
            }
        });
        mTabLayoutMediator.attach();
        // TabLayout only supports colorDrawable in xml. And if we set the color in the drawable by
        // setSelectedTabIndicator method, it doesn't apply the color. So, we set color in xml and
        // set the drawable for the shape here.
        tabLayout.setSelectedTabIndicator(R.drawable.picker_tab_indicator);
    }

    @Override
    public void onDestroyView() {
        mTabLayoutMediator.detach();
        super.onDestroyView();
    }

    /**
     * Create the fragment and add it into the FragmentManager
     *
     * @param fm the fragment manager
     */
    public static void show(FragmentManager fm) {
        final FragmentTransaction ft = fm.beginTransaction();
        final TabContainerFragment fragment = new TabContainerFragment();
        ft.replace(R.id.fragment_container, fragment, TAG);
        ft.commitAllowingStateLoss();
    }

    private static class AnimationPageTransformer implements ViewPager2.PageTransformer {

        @Override
        public void transformPage(@NonNull View view, float pos) {
            view.setAlpha(1.0f - Math.abs(pos));
        }
    }

    private static class NestedScrollPageTransformer implements ViewPager2.PageTransformer {
        private Field mFieldNestedScrollingChildRef;
        private BottomSheetBehavior mBottomSheetBehavior;

        public NestedScrollPageTransformer(BottomSheetBehavior bottomSheetBehavior, Field field) {
            mBottomSheetBehavior = bottomSheetBehavior;
            mFieldNestedScrollingChildRef = field;
        }

        @Override
        public void transformPage(@NonNull View view, float pos) {
            // If pos != 0, it is not in current page, don't update the nested scrolling child
            // reference.
            if (pos != 0 || mFieldNestedScrollingChildRef == null) {
                return;
            }

            try {
                final View childView = view.findViewById(R.id.picker_tab_recyclerview);
                if (childView != null) {
                    mFieldNestedScrollingChildRef.set(mBottomSheetBehavior,
                            new WeakReference(childView));
                }
            } catch (IllegalAccessException ex) {
                Log.d(TAG, "Set nestedScrollingChildRef to BottomSheetBehavior fail", ex);
            }
        }
    }
}
