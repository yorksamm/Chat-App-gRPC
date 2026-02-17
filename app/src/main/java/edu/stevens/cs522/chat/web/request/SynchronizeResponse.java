package edu.stevens.cs522.chat.web.request;

import android.os.Parcel;

import edu.stevens.cs522.base.EnumUtils;

/**
 * Created by dduggan.
 */

public class SynchronizeResponse extends ChatServiceResponse {

    public static final String ID_LABEL = "id";

    public SynchronizeResponse() {
        super();
    }

    @Override
    public boolean isValid() { return true; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        EnumUtils.writeEnum(dest, ResponseType.SYNCHRONIZE);
        super.writeToParcel(dest, flags);
    }

    public SynchronizeResponse(Parcel in) {
        super(in);
    }

    public static Creator<SynchronizeResponse> CREATOR = new Creator<SynchronizeResponse>() {
        @Override
        public SynchronizeResponse createFromParcel(Parcel in) {
            EnumUtils.readEnum(ResponseType.class, in);
            return new SynchronizeResponse(in);
        }

        @Override
        public SynchronizeResponse[] newArray(int size) {
            return new SynchronizeResponse[size];
        }
    };
}
