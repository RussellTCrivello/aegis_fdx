package com.aegis.fdx.facade;

import com.aegis.fdx.facade.dto.AspectDto;
import com.aegis.fdx.facade.dto.SideDto;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Compatibility alias for {@link AspectFacade}.
 *
 * <p>This concept is named <strong>Aspect</strong> throughout the application. An
 * earlier revision called it a "side", and this class keeps that vocabulary working so
 * existing callers do not break. Every method delegates; there is no separate state and
 * no separate storage.
 *
 * @deprecated use {@link AspectFacade} directly. Retained only for source compatibility.
 */
@Deprecated(since = "1.1", forRemoval = false)
public final class SideFacade {

    private final AspectFacade delegate;

    public SideFacade(AspectFacade delegate) {
        this.delegate = delegate;
    }

    /** @deprecated use {@link AspectFacade#listAspects()} */
    @Deprecated
    public List<SideDto> getAllSides() {
        List<SideDto> out = new ArrayList<>();
        for (AspectDto a : delegate.listAspects()) {
            out.add(toSide(a));
        }
        return out;
    }

    /** @deprecated use {@link AspectFacade#createAspect(String, double)} */
    @Deprecated
    public int createSide(String name, double importance) {
        return delegate.createAspect(name, importance);
    }

    /** @deprecated use {@link AspectFacade#createAspect(String, double, LocalDate)} */
    @Deprecated
    public int createSide(String name, double importance, LocalDate dateCreation) {
        return delegate.createAspect(name, importance, dateCreation);
    }

    /** @deprecated use {@link AspectFacade#getAspect(int)} */
    @Deprecated
    public SideDto getSideById(int sideId) {
        return toSide(delegate.getAspect(sideId));
    }

    /** @deprecated use {@link AspectFacade#deleteAspect(int)} */
    @Deprecated
    public boolean deleteSide(int sideId) {
        return delegate.deleteAspect(sideId);
    }

    /** @deprecated use {@link AspectFacade#duplicateAspect(int)} */
    @Deprecated
    public int duplicateSide(int sideId) {
        return delegate.duplicateAspect(sideId);
    }

    /** The facade this alias forwards to. */
    public AspectFacade aspects() {
        return delegate;
    }

    private static SideDto toSide(AspectDto a) {
        return new SideDto(a.id(), a.name(), a.importance(), a.dateCreation());
    }
}
