package ru.vstu.adddict.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.vstu.adddict.dto.PageResponseDto;
import ru.vstu.adddict.dto.dictionary.*;
import ru.vstu.adddict.entity.dictionary.Dictionary;
import ru.vstu.adddict.exception.DictionaryNonExistException;
import ru.vstu.adddict.exception.NotAllowedException;
import ru.vstu.adddict.mapper.DictionaryMapper;
import ru.vstu.adddict.repository.DictionariesRepository;
import ru.vstu.adddict.validator.DictionaryValidator;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class DictionaryService {

    private final DictionaryValidator dictionaryValidator;

    private final DictionaryMapper dictionaryMapper;

    private final DictionariesRepository dictionariesRepository;

    private final int dictionariesPageSize;

    @Transactional
    public DictionaryDto createDictionary(CreateDictionaryRequestDto createDictionaryRequest) {
        dictionaryValidator.validateCreateDictionaryRequest(createDictionaryRequest).ifPresent(e -> {
                throw e;
        });

        Dictionary dictionary = dictionaryMapper.toDictionary(createDictionaryRequest);
        Dictionary savedDictionary = dictionariesRepository.save(dictionary);

        return dictionaryMapper.toDto(savedDictionary);
    }

    @Transactional // For caching in future
    public DictionaryDto getDictionary(GetDictionaryRequestDto getDictionaryRequestDto) {
        Optional<Dictionary> dictionaryInRepos = getDictionary(getDictionaryRequestDto.getId());

        if (dictionaryInRepos.isEmpty()) {
            throw new DictionaryNonExistException(getDictionaryRequestDto.getId());
        }

        Dictionary dictionary = dictionaryInRepos.get();

        if (forbiddenToGetByThisUser(dictionary, getDictionaryRequestDto.getRequestSenderId())) {
            throw new NotAllowedException("Can't let dictionary with id: "
                    + dictionary.getId()
                    + " to this user. This dictionary is private and belongs to other user.");
        }

        return dictionaryMapper.toDto(dictionary);
    }

    public boolean forbiddenToGetByThisUser(Dictionary dictionary, Long requestSenderId) {
        return !dictionary.isPublic() && !dictionary.isDictionaryOwner(requestSenderId);
    }

    @Transactional
    public DictionaryDto updateDictionary(Long dictionaryId, UpdateDictionaryRequestDto updateDictionaryRequestDto) {

        dictionaryValidator.validateUpdateDictionaryRequest(updateDictionaryRequestDto).ifPresent(e -> {
            throw e;
        });

        DictionaryDto updatedDictionary = updateDictionaryInRepository(dictionaryId, updateDictionaryRequestDto);

        return updatedDictionary;
    }

    private DictionaryDto updateDictionaryInRepository(Long dictionaryId, UpdateDictionaryRequestDto updateDictionaryRequestDto) {

        try {
            Dictionary updated = dictionariesRepository.updateWithLock(
                    dictionaryId,
                    persisted -> {
                        persisted = dictionaryMapper.fromUpdateRequest(persisted, updateDictionaryRequestDto);
                        if (!persisted.isDictionaryOwner(dictionaryId)) {
                            throw new NotAllowedException("Can't update dictionary with id: "
                                    + persisted.getId()
                                    + ". This dictionary belongs to other user.");
                        }
                        return persisted;
                    });

            return dictionaryMapper.toDto(updated);
        } catch (NoSuchElementException e) {
            throw new DictionaryNonExistException(dictionaryId);
        }
    }

    @Transactional
    public boolean deleteDictionary(Long id, Long userId) {
        Optional<Dictionary> optionalDictionary = getDictionary(id);

        if (optionalDictionary.isEmpty()) {
            throw new DictionaryNonExistException(id);
        }

        Dictionary dictionary = optionalDictionary.get();

        if (!dictionary.isDictionaryOwner(userId)) {
            throw new NotAllowedException("Can't delete dictionary with id: "
                    + dictionary.getId()
                    + ". This dictionary belongs to other user.");
        }

        dictionariesRepository.deleteById(id);

        return true;
    }

    private Optional<Dictionary> getDictionary(Long id) {
        return dictionariesRepository.findById(id);
    }

    public GetUserDictionariesResponseDto<DictionaryDto> getUserDictionaries(GetUserDictionariesRequestDto requestDto) {
        dictionaryValidator.validateGetUserDictionariesRequestDto(requestDto).ifPresent(e -> {
            throw e;
        });

        Page<Dictionary> page;
        if (requestDto.getUserId().equals(requestDto.getRequestSenderId())) {
            page = getOwnedDictionariesPageByUserId(requestDto.getUserId(), requestDto.getPage());
        } else {
            page = getPublicUserDictionariesByUserId(requestDto.getUserId(), requestDto.getPage());
        }

        List<DictionaryDto> notes = page
                .stream()
                .map(dictionaryMapper::toDto)
                .collect(Collectors.toList());

        return GetUserDictionariesResponseDto.<DictionaryDto>builder()
                .userId(requestDto.getUserId())
                .page(PageResponseDto.<DictionaryDto>builder()
                        .content(notes)
                        .page(page.getNumber())
                        .pageSize(notes.size())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .build()
                ).build();
    }

    private Page<Dictionary> getOwnedDictionariesPageByUserId(Long authorId, int page) {
        return dictionariesRepository.getDictionariesByAuthorId(authorId, PageRequest.of(page, dictionariesPageSize));
    }

    private Page<Dictionary> getPublicUserDictionariesByUserId(Long authorId, int page) {
        return dictionariesRepository.getDictionariesByAuthorIdAndIsPublic(authorId, true, PageRequest.of(page, dictionariesPageSize));
    }

    public GetUserDictionariesResponseDto<DictionaryDto> getUserSubscribedDictionaries(GetUserSubscribedDictionariesRequestDto requestDto) {
        dictionaryValidator.validateGetUserSubscribedDictionariesRequestDto(requestDto).ifPresent(e -> {
            throw e;
        });

        Page<Dictionary> page = dictionariesRepository.findSubscribedDictionaries(requestDto.getUserId(), PageRequest.of(requestDto.getPage(), dictionariesPageSize));

        List<DictionaryDto> dictionaries = page
                .stream()
                .map(dictionaryMapper::toDto)
                .collect(Collectors.toList());

        return GetUserDictionariesResponseDto.<DictionaryDto>builder()
                .userId(requestDto.getUserId())
                .page(PageResponseDto.<DictionaryDto>builder()
                        .content(dictionaries)
                        .page(page.getNumber())
                        .pageSize(dictionaries.size())
                        .totalElements(page.getTotalElements())
                        .totalPages(page.getTotalPages())
                        .build()
                ).build();
    }
}
