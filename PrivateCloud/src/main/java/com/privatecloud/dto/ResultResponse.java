package com.privatecloud.dto;

public class ResultResponse<T> {

    private int code;
    private String message;
    private T data;

    public ResultResponse() {}

    public ResultResponse(int code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
    }

    public static <T> ResultResponse<T> success(T data) {
        return new ResultResponse<>(200, "success", data);
    }

    public static <T> ResultResponse<T> fail(String message) {
        return new ResultResponse<>(500, message, null);
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }

    public T getData() {
        return data;
    }
}