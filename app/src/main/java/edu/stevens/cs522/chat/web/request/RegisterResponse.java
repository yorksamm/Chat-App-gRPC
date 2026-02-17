package edu.stevens.cs522.chat.web.request;

import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import edu.stevens.cs522.base.EnumUtils;

/**
 * Created by dduggan.
 */

public class RegisterResponse extends ChatServiceResponse {

    public RegisterResponse() {
        super();
    }

    public Uri chatserverUrl;

    @Override
    public boolean isValid() { return true; }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        EnumUtils.writeEnum(dest, ResponseType.REGISTER);
        dest.writeString(chatserverUrl.toString());
    }

    public RegisterResponse(Parcel in) {
        super(in);
        chatserverUrl = Uri.parse(in.readString());
    }

    public static Parcelable.Creator<RegisterResponse> CREATOR = new Parcelable.Creator<RegisterResponse>() {
        @Override
        public RegisterResponse createFromParcel(Parcel in) {
            EnumUtils.readEnum(ResponseType.class, in);
            return new RegisterResponse(in);
        }

        @Override
        public RegisterResponse[] newArray(int size) {
            return new RegisterResponse[size];
        }
    };
}
