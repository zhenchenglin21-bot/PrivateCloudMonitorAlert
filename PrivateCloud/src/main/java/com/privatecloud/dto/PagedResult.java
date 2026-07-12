package com.privatecloud.dto;

import java.util.List;

public class PagedResult<T> {
    private List<T> items;
    private long total;
    private int page;
    private int size;

    public PagedResult(List<T> items, long total, int page, int size) {
        this.items = items;
        this.total = total;
        this.page = page;
        this.size = size;
    }

    public List<T> getItems() {
        return items;
    }

    public long getTotal() {
        return total;
    }

    public int getPage() {
        return page;
    }

    public int getSize() {
        return size;
    }
}
