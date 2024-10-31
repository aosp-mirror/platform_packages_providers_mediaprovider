/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.providers.media.search;

/**
 * Helper class for picker search which holds all the relevant media status table constants like
 * the table name and the table columns.
 */
public class SearchUtilConstants {
    /*
     Name of the media status table.
     */
    public final String MEDIA_STATUS_TABLE = "search_index_processing_status";
    /*
     Column holding the media_id assigned to each media item on insertion into the files table.
     */
    public final String MEDIA_ID_COLUMN = "media_id";
    /*
     Column holding the display name assigned to each media item on insertion into the files table.
     */
    public final String DISPLAY_NAME_COLUMN = "display_name";
    /*
     Column holding the date at which the media item is first created and inserted into the
     files table.
     */
    public final String DATE_TAKEN_COLUMN = "date_taken";
    /*
     Column holding the mime type of each media item referenced from the files table..
     */
    public final String MIME_TYPE_COLUMN = "mime_type";
    /*
     Columns holding the size in bytes of each media item referenced from the files table.
     */
    public final String SIZE_COLUMN = "size";
    /*
     Column holding the generation number of each media item referenced from the files table.
     */
    public final String GENERATION_NUMBER_COLUMN = "generation_number";
    /*
      Column holding the latitude of the media item referenced from the files table.
     */
    public final String LATITUDE = "latitude";
    /*
     Column holding the longitude of the media item referenced from the files table.
     */
    public final String LONGITUDE = "longitude";
    /*
     Column denoting the metadata processing status of each media item. By metadata, we mean the
     display_name, mime_type and date_taken for each media item.
     It is 0 in case of unprocessed metadata and set to 1 when metadata is processed.
     */
    public final String METADATA_PROCESSING_STATUS_COLUMN = "metadata_processing_status";
    /*
     Column denoting the location processing status of each media item. By location, we mean the
     geolocation of the media item.
     It is 0 in case of unprocessed location and set to 1 when location is processed.
     */
    public final String LOCATION_PROCESSING_STATUS_COLUMN = "location_processing_status";
    /*
     Column denoting the ocr processing status of each media item. OCR refers to the text included
     in the media item and is relevant only for images.
     It is 0 in case of unprocessed media text and set to 1 when the media item is processed.
     */
    public final String OCR_PROCESSING_STATUS_COLUMN = "ocr_latin_processing_status";
    /*
     Column denoting the label processing status of each media item. Labels refer to descriptive
     terms for a media item and is relevant for images only.
     It is 0 in case the media item has not been processed for corresponding labels and 1 otherwise.
     */
    public final String LABEL_PROCESSING_STATUS_COLUMN = "label_processing_status";
}
