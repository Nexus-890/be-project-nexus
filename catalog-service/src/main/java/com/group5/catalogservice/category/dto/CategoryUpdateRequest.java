package com.group5.catalogservice.category.dto;

import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Getter;

import java.util.UUID;

/** Partial update: an omitted property keeps its current value. */
public class CategoryUpdateRequest {
    @Getter
    @Size(max = 150)
    @Pattern(regexp = "(?s).*\\S.*", message = "Name must not be blank")
    private String name;

    @Getter
    @Size(max = 100)
    @Pattern(regexp = "^[a-z0-9]+(?:-[a-z0-9]+)*$", message = "Invalid slug format")
    private String slug;

    @Getter
    @Size(max = 1000)
    private String description;

    @Getter
    private UUID parentId;

    //boolean default = FALSE - chỗ này để đánh dấu là có gửi field đó lên hay không ( True là có )
    private boolean namePresent;
    private boolean slugPresent;
    private boolean descriptionPresent;
    private boolean parentIdPresent;

    /*
    Nulls.FAIL ->  {"name":null} thì báo lỗi,

    chỗ này nghĩa là nếu k truyền name lên thì Setter không được gọi -> namePresent = FALSE ( không đổi giá trị )
    */
    @JsonSetter(value = "name", nulls = Nulls.FAIL)
    public void setName(String name) {
        this.name = name;
        this.namePresent = true;
    }

    @JsonSetter(value = "slug", nulls = Nulls.FAIL)
    public void setSlug(String slug) {
        this.slug = slug;
        this.slugPresent = true;
    }

    @JsonSetter(value = "description", nulls = Nulls.SET)
    public void setDescription(String description) {
        this.description = description;
        this.descriptionPresent = true;
    }
    /*
    Nulls.SET -> {}, {"parentId":null} -> vẫn set giá trị là null
    tuy nhiên {} : là không truyền thì sẽ không gọi setter và parentIdPresent vẫn = false -> ko đổi giá trị
    {"parentId":null} -> có truyền giá trị null, parentIdPresent = True
    {"parentId":"UUID mới"} -> có truyền giá trị UUID mới, parentIdPresent = True

    */

    @JsonSetter(value = "parentId", nulls = Nulls.SET)
    public void setParentId(UUID parentId) {
        this.parentId = parentId;
        this.parentIdPresent = true;
    }

    //mấy cái dưới đây để bên service gọi check True False ( lấy giá trị mới hay cũ )
    public boolean hasName() { return namePresent; }
    public boolean hasSlug() { return slugPresent; }
    public boolean hasDescription() { return descriptionPresent; }
    public boolean hasParentId() { return parentIdPresent; }
}
