package com.example.springboot_cy_marketplace.dto;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

public class OffsetBasedPageRequestDTO implements Pageable {
    private int limit;
    private int offset;
//    private Sort sort = new Sort(Sort.Direction.ASC, "id");

    public OffsetBasedPageRequestDTO(int limit, int offset) {
        if (limit < 1) {
            throw new IllegalArgumentException("Limit must not be less than one!");
        }
        if (offset < 0) {
            throw new IllegalArgumentException("Offset index must not be less than zero!");
        }
        this.limit = limit;
        this.offset = offset;
    }

    @Override
    public int getPageNumber() {
        return offset / limit;
    }
    @Override
    public int getPageSize() {
        return limit;
    }
    @Override
    public long getOffset() {
        return offset;
    }
    @Override
    public Sort getSort() {
        return Sort.by("id").ascending();
    }
    @Override
    public Pageable next() {
        // Typecast possible because number of entries cannot be bigger than integer (primary key is integer)
        return new OffsetBasedPageRequestDTO(getPageSize(), (int) (getOffset() + getPageSize()));
    }
    public Pageable previous() {
        // The integers are positive. Subtracting does not let them become bigger than integer.
        return hasPrevious() ?
                new OffsetBasedPageRequestDTO(getPageSize(), (int) (getOffset() - getPageSize())): this;
    }
    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? previous() : first();
    }
    @Override
    public Pageable first() {
        return new OffsetBasedPageRequestDTO(getPageSize(), 0);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return null;
    }

    @Override
    public boolean hasPrevious() {
        return offset > limit;
    }
}
