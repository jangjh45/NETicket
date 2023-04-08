package com.example.neticket.event.service;

import com.example.neticket.event.dto.DetailEventResponseDto;
import com.example.neticket.event.dto.EventRequestDto;
import com.example.neticket.event.dto.EventResponseDto;
import com.example.neticket.event.dto.MessageResponseDto;
import com.example.neticket.event.entity.Event;
import com.example.neticket.event.entity.TicketInfo;
import com.example.neticket.event.repository.EventRepository;
import com.example.neticket.event.repository.TicketInfoRepository;
import com.example.neticket.user.entity.User;
import com.example.neticket.user.entity.UserRoleEnum;
import java.io.IOException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
@RequiredArgsConstructor
public class EventService {

  private final EventRepository eventRepository;
  private final TicketInfoRepository ticketInfoRepository;
  private final S3Client s3Client;
  private final String bucketName;

  // S3 이미지 업로드 메서드
  private String S3ImageUpload(MultipartFile image) throws IOException {
    String key = UUID.randomUUID() + "_" + image.getOriginalFilename(); // 또는 다른 고유한 키 생성 방법

    PutObjectRequest request = PutObjectRequest.builder()
        .bucket(bucketName)
        .key("uploaded-image/" + key)
        .contentType(image.getContentType())
        .contentLength(image.getSize())
        .build();

    s3Client.putObject(request,
        RequestBody.fromInputStream(image.getInputStream(), image.getSize()));
    return key;
  }

  // S3 이미지 삭제 메서드
  private void S3ImageDelete(String img) {
    String div = "uploaded-image";
    s3Client.deleteObject(
        DeleteObjectRequest.builder().bucket(bucketName).key(div + "/" + img).build());

  }

  // 메인페이지 조회
  @Transactional(readOnly = true)
  public Page<EventResponseDto> getEvents(int page) {
    // ticketInfo의 isAvailable이 true인 Event를 ticketInfo의 date가 가장 빠른 순서대로 정렬하여 Page<EventResponseDto>로 반환
    Pageable pageable = PageRequest.of(page, 4);
    return eventRepository.findAllByAvailableOrderByticketInfoDate(pageable)
        .map(EventResponseDto::new);

  }

  // 상세 페이지 조회
  @Transactional(readOnly = true)
  public DetailEventResponseDto getDetailEvent(Long eventId) {
    return eventRepository.findById(eventId)
        .map(DetailEventResponseDto::new)
        .orElseThrow(() -> new IllegalArgumentException("조회하려는 공연 정보가 없습니다."));
  }

  // 공연 추가하기
  @Transactional
  public MessageResponseDto addEvent(EventRequestDto eventRequestDto, User user,
      MultipartFile image) throws IOException{
    checkAdmin(user);
    String key = S3ImageUpload(image);
    Event event = eventRepository.save(new Event(eventRequestDto, key));
    ticketInfoRepository.save(new TicketInfo(eventRequestDto, event));

    return new MessageResponseDto(HttpStatus.CREATED, "공연 추가 완료했습니다.");
  }

  //  공연삭제
  @Transactional
  public MessageResponseDto deleteEvent(Long eventId, User user) {
    checkAdmin(user);
    Event event = eventRepository.findById(eventId).orElseThrow(
        () -> new IllegalArgumentException("해당 공연 정보가 없습니다.")
    );

    S3ImageDelete(event.getImage());
    ticketInfoRepository.delete(event.getTicketInfo());
    eventRepository.delete(event);
    return new MessageResponseDto(HttpStatus.OK,"공연정보와 예매정보가 정상적으로 삭제되었습니다.");
  }

  // 검색기능
  @Transactional(readOnly = true)
  public Page<EventResponseDto> searchEvents(String keyword, int page, String sortBy,
      boolean isAsc) {
    Sort.Direction direction = isAsc ? Sort.Direction.ASC : Sort.Direction.DESC;
    Sort sort = Sort.by(direction, sortBy);
    Pageable pageable = PageRequest.of(page, 4, sort);
    return eventRepository.findAllByTitleOrPlaceContaining(keyword, pageable)
        .map(EventResponseDto::new);

  }

  public void checkAdmin(User user) {
    if (!user.getRole().equals(UserRoleEnum.ADMIN)) {
      throw new IllegalArgumentException("관리자가 아닙니다.");
    }
  }

}
