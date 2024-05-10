/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.providers.media.photopicker.data;

import static com.android.providers.media.photopicker.data.PickerDbFacade.QueryFilterBuilder.INT_DEFAULT;

/**
 * Holder for parameters required for pagination of photos and category items grid recyclerView in
 * photoPicker.
 */
public class PaginationParameters {
    private int mPageSize = INT_DEFAULT;
    private long mDateBeforeMs = Long.MIN_VALUE;
    private int mRowId = INT_DEFAULT;
    public static final int PAGINATION_PAGE_SIZE_ITEMS = 600;

    public static final int PAGINATION_PAGE_SIZE_ALBUM_ITEMS = 600;

    /**
     * Instantiates UI pagination parameters for photoPicker. Use this when all the fields needs to
     * be set to default, i.e. to return complete list of items.
     */
    public PaginationParameters() {
    }

    /**
     * Instantiates UI pagination parameters for photoPicker.
     *
     * <p>The parameters will be used similar to this sample query :
     * {@code SELECT * FROM TABLE_NAME WHERE (column_date_before_ms < dateBeforeMs
     * OR ( column_date_before_ms = dateBeforeMs AND column_row_id < rowID)) LIMIT pageSize;}
     *
     * @param pageSize     used to represent the upper limit of the number of rows that should be
     *                     returned by the query. Set as -1 to ignore this parameter in the query.
     * @param dateBeforeMs when set items with date less that this will be returned. Set as -1 to
     *                     ignore this parameter in the query.
     * @param rowId        when set items with id less than this will be returned. Set as -1 to
     *                     ignore this parameter in the query.
     */
    public PaginationParameters(int pageSize, long dateBeforeMs, int rowId) {
        mPageSize = pageSize;
        mDateBeforeMs = dateBeforeMs;
        mRowId = rowId;
    }

    /**
     * Instantiates UI pagination parameters for photoPicker.
     *
     * <p>When using this constructor the value for pageSize will be the default value i.e. -1.</p>
     *
     * @param dateBeforeMs when set items with date less that this will be returned. Set as -1 to
     *                     ignore this parameter in the query.
     * @param rowId        when set items with id less than this will be returned. Set as -1 to
     *                     ignore this parameter in the query.
     */
    public PaginationParameters(long dateBeforeMs, int rowId) {
        this(PAGINATION_PAGE_SIZE_ITEMS, dateBeforeMs, rowId);
    }

    /**
     * @return page size for pagination. It is used as the LIMIT clause in the query to database.
     */
    public int getPageSize() {
        return mPageSize;
    }

    /**
     * @return date in ms which can be used as the parameter in the query to load items.
     *
     * <p>This is combination with row id is used to find the next page of data.</p>
     *
     * <b>Note: This parameter is only used in the query if the row id is set. Else it is
     * ignored.</b>
     */
    public Long getDateBeforeMs() {
        return mDateBeforeMs;
    }

    /**
     * @return row id which can be used as the parameter in the query to load items.
     *
     * <p>This is combination with date_taken_before_ms is used to find the next page of data.</p>
     *
     * <p>When the {@link PaginationParameters#mDateBeforeMs} for two rows is same, this
     * parameter is used to figure out which row to return.</p>
     */
    public int getRowId() {
        return mRowId;
    }
}
